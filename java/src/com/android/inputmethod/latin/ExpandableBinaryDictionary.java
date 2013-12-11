/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.android.inputmethod.annotations.UsedForTesting;
import com.android.inputmethod.keyboard.ProximityInfo;
import com.android.inputmethod.latin.BinaryDictionary.LanguageModelParam;
import com.android.inputmethod.latin.makedict.FormatSpec;
import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import com.android.inputmethod.latin.utils.AsyncResultHolder;
import com.android.inputmethod.latin.utils.CollectionUtils;
import com.android.inputmethod.latin.utils.FileUtils;
import com.android.inputmethod.latin.utils.PrioritizedSerialExecutor;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract base class for an expandable dictionary that can be created and updated dynamically
 * during runtime. When updated it automatically generates a new binary dictionary to handle future
 * queries in native code. This binary dictionary is written to internal storage, and potentially
 * shared across multiple ExpandableBinaryDictionary instances. Updates to each dictionary filename
 * are controlled across multiple instances to ensure that only one instance can update the same
 * dictionary at the same time.
 */
abstract public class ExpandableBinaryDictionary extends Dictionary {

    /** Used for Log actions from this class */
    private static final String TAG = ExpandableBinaryDictionary.class.getSimpleName();

    /** Whether to print debug output to log */
    private static boolean DEBUG = false;
    private static final boolean DBG_STRESS_TEST = false;

    private static final int TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS = 100;

    /**
     * The maximum length of a word in this dictionary.
     */
    protected static final int MAX_WORD_LENGTH = Constants.DICTIONARY_MAX_WORD_LENGTH;

    private static final int DICTIONARY_FORMAT_VERSION = FormatSpec.VERSION4;

    /**
     * A static map of update controllers, each of which records the time of accesses to a single
     * binary dictionary file and tracks whether the file is regenerating. The key for this map is
     * the filename and the value is the shared dictionary time recorder associated with that
     * filename.
     */
    private static final ConcurrentHashMap<String, DictionaryUpdateController>
            sFilenameDictionaryUpdateControllerMap = CollectionUtils.newConcurrentHashMap();

    private static final ConcurrentHashMap<String, PrioritizedSerialExecutor>
            sFilenameExecutorMap = CollectionUtils.newConcurrentHashMap();

    /** The application context. */
    protected final Context mContext;

    /**
     * The binary dictionary generated dynamically from the fusion dictionary. This is used to
     * answer unigram and bigram queries.
     */
    private BinaryDictionary mBinaryDictionary;

    // TODO: Remove and handle dictionaries in native code.
    /** The in-memory dictionary used to generate the binary dictionary. */
    protected AbstractDictionaryWriter mDictionaryWriter;

    /**
     * The name of this dictionary, used as the filename for storing the binary dictionary. Multiple
     * dictionary instances with the same filename is supported, with access controlled by
     * DictionaryTimeRecorder.
     */
    private final String mFilename;

    /** Dictionary locale */
    private final Locale mLocale;

    /** Whether to support dynamically updating the dictionary */
    private final boolean mIsUpdatable;

    // TODO: remove, once dynamic operations is serialized
    /** Controls updating the shared binary dictionary file across multiple instances. */
    private final DictionaryUpdateController mFilenameDictionaryUpdateController;

    // TODO: remove, once dynamic operations is serialized
    /** Controls updating the local binary dictionary for this instance. */
    private final DictionaryUpdateController mPerInstanceDictionaryUpdateController =
            new DictionaryUpdateController();

    /* A extension for a binary dictionary file. */
    public static final String DICT_FILE_EXTENSION = ".dict";

    private final AtomicReference<Runnable> mUnfinishedFlushingTask =
            new AtomicReference<Runnable>();

    /**
     * Abstract method for loading the unigrams and bigrams of a given dictionary in a background
     * thread.
     */
    protected abstract void loadDictionaryAsync();

    /**
     * Indicates that the source dictionary content has changed and a rebuild of the binary file is
     * required. If it returns false, the next reload will only read the current binary dictionary
     * from file. Note that the shared binary dictionary is locked when this is called.
     */
    protected abstract boolean hasContentChanged();

    protected boolean matchesExpectedBinaryDictFormatVersionForThisType(final int formatVersion) {
        // This class is using format 2 because it's used by the User and Contacts dictionary
        // only, which right now use format 2 (dicts using format 4 use Decaying*, which overrides
        // this method).
        // TODO: Migrate these dicts to ver4 format, and remove this function.
        return formatVersion == 2;
    }

    public boolean isValidDictionary() {
        return mBinaryDictionary.isValidDictionary();
    }

    protected String getFileNameExtensionToOpenDict() {
        return "";
    }

    /**
     * Gets the dictionary update controller for the given filename.
     */
    private static DictionaryUpdateController getDictionaryUpdateController(
            String filename) {
        DictionaryUpdateController recorder = sFilenameDictionaryUpdateControllerMap.get(filename);
        if (recorder == null) {
            synchronized(sFilenameDictionaryUpdateControllerMap) {
                recorder = new DictionaryUpdateController();
                sFilenameDictionaryUpdateControllerMap.put(filename, recorder);
            }
        }
        return recorder;
    }

    /**
     * Gets the executor for the given filename.
     */
    private static PrioritizedSerialExecutor getExecutor(final String filename) {
        PrioritizedSerialExecutor executor = sFilenameExecutorMap.get(filename);
        if (executor == null) {
            synchronized(sFilenameExecutorMap) {
                executor = new PrioritizedSerialExecutor();
                sFilenameExecutorMap.put(filename, executor);
            }
        }
        return executor;
    }

    private static AbstractDictionaryWriter getDictionaryWriter(final Context context,
            final boolean isDynamicPersonalizationDictionary) {
        if (isDynamicPersonalizationDictionary) {
             return null;
        } else {
            return new DictionaryWriter(context);
        }
    }

    /**
     * Creates a new expandable binary dictionary.
     *
     * @param context The application context of the parent.
     * @param filename The filename for this binary dictionary. Multiple dictionaries with the same
     *        filename is supported.
     * @param locale the dictionary locale.
     * @param dictType the dictionary type, as a human-readable string
     * @param isUpdatable whether to support dynamically updating the dictionary. Please note that
     *        dynamic dictionary has negative effects on memory space and computation time.
     */
    public ExpandableBinaryDictionary(final Context context, final String filename,
            final Locale locale, final String dictType, final boolean isUpdatable) {
        super(dictType);
        mFilename = filename;
        mContext = context;
        mLocale = locale;
        mIsUpdatable = isUpdatable;
        mBinaryDictionary = null;
        mFilenameDictionaryUpdateController = getDictionaryUpdateController(filename);
        // Currently, only dynamic personalization dictionary is updatable.
        mDictionaryWriter = getDictionaryWriter(context, isUpdatable);
    }

    protected static String getFilenameWithLocale(final String name, final Locale locale) {
        return name + "." + locale.toString() + DICT_FILE_EXTENSION;
    }

    /**
     * Closes and cleans up the binary dictionary.
     */
    @Override
    public void close() {
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                if (mBinaryDictionary!= null) {
                    mBinaryDictionary.close();
                    mBinaryDictionary = null;
                }
            }
        });
    }

    protected void closeBinaryDictionary() {
        // Ensure that no other threads are accessing the local binary dictionary.
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                if (mBinaryDictionary != null) {
                    mBinaryDictionary.close();
                    mBinaryDictionary = null;
                }
            }
        });
    }

    protected Map<String, String> getHeaderAttributeMap() {
        HashMap<String, String> attributeMap = new HashMap<String, String>();
        attributeMap.put(FormatSpec.FileHeader.DICTIONARY_ID_ATTRIBUTE, mFilename);
        attributeMap.put(FormatSpec.FileHeader.DICTIONARY_LOCALE_ATTRIBUTE, mLocale.toString());
        attributeMap.put(FormatSpec.FileHeader.DICTIONARY_VERSION_ATTRIBUTE,
                String.valueOf(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())));
        return attributeMap;
    }

    protected void clear() {
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                if (mDictionaryWriter == null) {
                    mBinaryDictionary.close();
                    final File file = new File(mContext.getFilesDir(), mFilename);
                    file.delete();
                    BinaryDictionary.createEmptyDictFile(file.getAbsolutePath(),
                            DICTIONARY_FORMAT_VERSION, getHeaderAttributeMap());
                    // We have 'fileToOpen' in addition to 'file' for the v4 dictionary format
                    // where 'file' is a directory, and 'fileToOpen' is a normal file.
                    final File fileToOpen = new File(mContext.getFilesDir(), mFilename
                            + getFileNameExtensionToOpenDict());
                    // TODO: Make BinaryDictionary's constructor be able to accept filename
                    // without extension.
                    mBinaryDictionary = new BinaryDictionary(
                            fileToOpen.getAbsolutePath(), 0 /* offset */, fileToOpen.length(),
                            true /* useFullEditDistance */, null, mDictType, mIsUpdatable);
                } else {
                    mDictionaryWriter.clear();
                }
            }
        });
    }

    /**
     * Adds a word unigram to the dictionary. Used for loading a dictionary.
     * @param word The word to add.
     * @param shortcutTarget A shortcut target for this word, or null if none.
     * @param frequency The frequency for this unigram.
     * @param shortcutFreq The frequency of the shortcut (0~15, with 15 = whitelist). Ignored
     *   if shortcutTarget is null.
     * @param isNotAWord true if this is not a word, i.e. shortcut only.
     */
    protected void addWord(final String word, final String shortcutTarget,
            final int frequency, final int shortcutFreq, final boolean isNotAWord) {
        mDictionaryWriter.addUnigramWord(word, shortcutTarget, frequency, shortcutFreq, isNotAWord);
    }

    /**
     * Adds a word bigram in the dictionary. Used for loading a dictionary.
     */
    protected void addBigram(final String prevWord, final String word, final int frequency,
            final long lastModifiedTime) {
        mDictionaryWriter.addBigramWords(prevWord, word, frequency, true /* isValid */,
                lastModifiedTime);
    }

    /**
     * Check whether GC is needed and run GC if required.
     */
    protected void runGCIfRequired(final boolean mindsBlockByGC) {
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                runGCIfRequiredInternalLocked(mindsBlockByGC);
            }
        });
    }

    private void runGCIfRequiredInternalLocked(final boolean mindsBlockByGC) {
        // Calls to needsToRunGC() need to be serialized.
        if (mBinaryDictionary.needsToRunGC(mindsBlockByGC)) {
            if (setProcessingLargeTaskIfNot()) {
                // Run GC after currently existing time sensitive operations.
                getExecutor(mFilename).executePrioritized(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mBinaryDictionary.flushWithGC();
                        } finally {
                            mFilenameDictionaryUpdateController.mProcessingLargeTask.set(false);
                        }
                    }
                });
            }
        }
    }

    /**
     * Dynamically adds a word unigram to the dictionary. May overwrite an existing entry.
     */
    protected void addWordDynamically(final String word, final int frequency,
            final String shortcutTarget, final int shortcutFreq, final boolean isNotAWord,
            final boolean isBlacklisted, final int timestamp) {
        if (!mIsUpdatable) {
            Log.w(TAG, "addWordDynamically is called for non-updatable dictionary: " + mFilename);
            return;
        }
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                runGCIfRequiredInternalLocked(true /* mindsBlockByGC */);
                mBinaryDictionary.addUnigramWord(word, frequency, shortcutTarget, shortcutFreq,
                        isNotAWord, isBlacklisted, timestamp);
            }
        });
    }

    /**
     * Dynamically adds a word bigram in the dictionary. May overwrite an existing entry.
     */
    protected void addBigramDynamically(final String word0, final String word1,
            final int frequency, final int timestamp) {
        if (!mIsUpdatable) {
            Log.w(TAG, "addBigramDynamically is called for non-updatable dictionary: "
                    + mFilename);
            return;
        }
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                runGCIfRequiredInternalLocked(true /* mindsBlockByGC */);
                mBinaryDictionary.addBigramWords(word0, word1, frequency, timestamp);
            }
        });
    }

    /**
     * Dynamically remove a word bigram in the dictionary.
     */
    protected void removeBigramDynamically(final String word0, final String word1) {
        if (!mIsUpdatable) {
            Log.w(TAG, "removeBigramDynamically is called for non-updatable dictionary: "
                    + mFilename);
            return;
        }
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                runGCIfRequiredInternalLocked(true /* mindsBlockByGC */);
                mBinaryDictionary.removeBigramWords(word0, word1);
            }
        });
    }

    public interface AddMultipleDictionaryEntriesCallback {
        public void onFinished();
    }

    /**
     * Dynamically add multiple entries to the dictionary.
     */
    protected void addMultipleDictionaryEntriesDynamically(
            final ArrayList<LanguageModelParam> languageModelParams,
            final AddMultipleDictionaryEntriesCallback callback) {
        if (!mIsUpdatable) {
            Log.w(TAG, "addMultipleDictionaryEntriesDynamically is called for non-updatable " +
                    "dictionary: " + mFilename);
            return;
        }
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                final boolean locked = setProcessingLargeTaskIfNot();
                try {
                    mBinaryDictionary.addMultipleDictionaryEntries(
                            languageModelParams.toArray(
                                    new LanguageModelParam[languageModelParams.size()]));
                } finally {
                    if (callback != null) {
                        callback.onFinished();
                    }
                    if (locked) {
                        mFilenameDictionaryUpdateController.mProcessingLargeTask.set(false);
                    }
                }
            }
        });
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestionsWithSessionId(final WordComposer composer,
            final String prevWord, final ProximityInfo proximityInfo,
            final boolean blockOffensiveWords, final int[] additionalFeaturesOptions,
            final int sessionId) {
        reloadDictionaryIfRequired();
        if (processingLargeTask()) {
            return null;
        }
        final AsyncResultHolder<ArrayList<SuggestedWordInfo>> holder =
                new AsyncResultHolder<ArrayList<SuggestedWordInfo>>();
        getExecutor(mFilename).executePrioritized(new Runnable() {
            @Override
            public void run() {
                if (mBinaryDictionary == null) {
                    holder.set(null);
                    return;
                }
                final ArrayList<SuggestedWordInfo> binarySuggestion =
                        mBinaryDictionary.getSuggestionsWithSessionId(composer, prevWord,
                                proximityInfo, blockOffensiveWords, additionalFeaturesOptions,
                                sessionId);
                holder.set(binarySuggestion);
            }
        });
        return holder.get(null, TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS);
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestions(final WordComposer composer,
            final String prevWord, final ProximityInfo proximityInfo,
            final boolean blockOffensiveWords, final int[] additionalFeaturesOptions) {
        return getSuggestionsWithSessionId(composer, prevWord, proximityInfo, blockOffensiveWords,
                additionalFeaturesOptions, 0 /* sessionId */);
    }

    @Override
    public boolean isValidWord(final String word) {
        reloadDictionaryIfRequired();
        return isValidWordInner(word);
    }

    protected boolean isValidWordInner(final String word) {
        if (processingLargeTask()) {
            return false;
        }
        final AsyncResultHolder<Boolean> holder = new AsyncResultHolder<Boolean>();
        getExecutor(mFilename).executePrioritized(new Runnable() {
            @Override
            public void run() {
                holder.set(isValidWordLocked(word));
            }
        });
        return holder.get(false, TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS);
    }

    protected boolean isValidWordLocked(final String word) {
        if (mBinaryDictionary == null) return false;
        return mBinaryDictionary.isValidWord(word);
    }

    protected boolean isValidBigramLocked(final String word1, final String word2) {
        if (mBinaryDictionary == null) return false;
        return mBinaryDictionary.isValidBigram(word1, word2);
    }

    /**
     * Load the current binary dictionary from internal storage in a background thread. If no binary
     * dictionary exists, this method will generate one.
     */
    protected void loadDictionary() {
        mPerInstanceDictionaryUpdateController.mLastUpdateRequestTime = SystemClock.uptimeMillis();
        reloadDictionaryIfRequired();
    }

    /**
     * Loads the current binary dictionary from internal storage. Assumes the dictionary file
     * exists.
     */
    private void loadBinaryDictionary() {
        if (DEBUG) {
            Log.d(TAG, "Loading binary dictionary: " + mFilename + " request="
                    + mFilenameDictionaryUpdateController.mLastUpdateRequestTime + " update="
                    + mFilenameDictionaryUpdateController.mLastUpdateTime);
        }
        if (DBG_STRESS_TEST) {
            // Test if this class does not cause problems when it takes long time to load binary
            // dictionary.
            try {
                Log.w(TAG, "Start stress in loading: " + mFilename);
                Thread.sleep(15000);
                Log.w(TAG, "End stress in loading");
            } catch (InterruptedException e) {
            }
        }

        final File file = new File(mContext.getFilesDir(), mFilename
                + getFileNameExtensionToOpenDict());
        final String filename = file.getAbsolutePath();
        final long length = file.length();

        // Build the new binary dictionary
        final BinaryDictionary newBinaryDictionary = new BinaryDictionary(filename, 0 /* offset */,
                length, true /* useFullEditDistance */, null, mDictType, mIsUpdatable);

        // Ensure all threads accessing the current dictionary have finished before
        // swapping in the new one.
        // TODO: Ensure multi-thread assignment of mBinaryDictionary.
        final BinaryDictionary oldBinaryDictionary = mBinaryDictionary;
        getExecutor(mFilename).executePrioritized(new Runnable() {
            @Override
            public void run() {
                mBinaryDictionary = newBinaryDictionary;
                if (oldBinaryDictionary != null) {
                    oldBinaryDictionary.close();
                }
            }
        });
    }

    /**
     * Abstract method for checking if it is required to reload the dictionary before writing
     * a binary dictionary.
     */
    abstract protected boolean needsToReloadBeforeWriting();

    /**
     * Writes a new binary dictionary based on the contents of the fusion dictionary.
     */
    private void writeBinaryDictionary() {
        if (DEBUG) {
            Log.d(TAG, "Generating binary dictionary: " + mFilename + " request="
                    + mFilenameDictionaryUpdateController.mLastUpdateRequestTime + " update="
                    + mFilenameDictionaryUpdateController.mLastUpdateTime);
        }
        if (needsToReloadBeforeWriting()) {
            mDictionaryWriter.clear();
            loadDictionaryAsync();
            mDictionaryWriter.write(mFilename, getHeaderAttributeMap());
        } else {
            if (mBinaryDictionary == null || !isValidDictionary()
                    // TODO: remove the check below
                    || !matchesExpectedBinaryDictFormatVersionForThisType(
                            mBinaryDictionary.getFormatVersion())) {
                final File file = new File(mContext.getFilesDir(), mFilename);
                if (!FileUtils.deleteRecursively(file)) {
                    Log.e(TAG, "Can't remove a file: " + file.getName());
                }
                BinaryDictionary.createEmptyDictFile(file.getAbsolutePath(),
                        DICTIONARY_FORMAT_VERSION, getHeaderAttributeMap());
            } else {
                if (mBinaryDictionary.needsToRunGC(false /* mindsBlockByGC */)) {
                    mBinaryDictionary.flushWithGC();
                } else {
                    mBinaryDictionary.flush();
                }
            }
        }
    }

    /**
     * Marks that the dictionary is out of date and requires a reload.
     *
     * @param requiresRebuild Indicates that the source dictionary content has changed and a rebuild
     *        of the binary file is required. If not true, the next reload process will only read
     *        the current binary dictionary from file.
     */
    protected void setRequiresReload(final boolean requiresRebuild) {
        final long time = SystemClock.uptimeMillis();
        mPerInstanceDictionaryUpdateController.mLastUpdateRequestTime = time;
        mFilenameDictionaryUpdateController.mLastUpdateRequestTime = time;
        if (DEBUG) {
            Log.d(TAG, "Reload request: " + mFilename + ": request=" + time + " update="
                    + mFilenameDictionaryUpdateController.mLastUpdateTime);
        }
    }

    /**
     * Reloads the dictionary if required.
     */
    public final void reloadDictionaryIfRequired() {
        if (!isReloadRequired()) return;
        if (setProcessingLargeTaskIfNot()) {
            reloadDictionary();
        }
    }

    /**
     * Returns whether a dictionary reload is required.
     */
    private boolean isReloadRequired() {
        return mBinaryDictionary == null || mPerInstanceDictionaryUpdateController.isOutOfDate();
    }

    private boolean processingLargeTask() {
        return mFilenameDictionaryUpdateController.mProcessingLargeTask.get();
    }

    // Returns whether the dictionary is being used for a large task. If true, we should not use
    // this dictionary for latency sensitive operations.
    private boolean setProcessingLargeTaskIfNot() {
        return mFilenameDictionaryUpdateController.mProcessingLargeTask.compareAndSet(
                false /* expect */ , true /* update */);
    }

    /**
     * Reloads the dictionary. Access is controlled on a per dictionary file basis and supports
     * concurrent calls from multiple instances that share the same dictionary file.
     */
    private final void reloadDictionary() {
        // Ensure that only one thread attempts to read or write to the shared binary dictionary
        // file at the same time.
        getExecutor(mFilename).execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final long time = SystemClock.uptimeMillis();
                    final boolean dictionaryFileExists = dictionaryFileExists();
                    if (mFilenameDictionaryUpdateController.isOutOfDate()
                            || !dictionaryFileExists) {
                        // If the shared dictionary file does not exist or is out of date, the
                        // first instance that acquires the lock will generate a new one.
                        if (hasContentChanged() || !dictionaryFileExists) {
                            // If the source content has changed or the dictionary does not exist,
                            // rebuild the binary dictionary. Empty dictionaries are supported (in
                            // the case where loadDictionaryAsync() adds nothing) in order to
                            // provide a uniform framework.
                            mFilenameDictionaryUpdateController.mLastUpdateTime = time;
                            writeBinaryDictionary();
                            loadBinaryDictionary();
                        } else {
                            // If not, the reload request was unnecessary so revert
                            // LastUpdateRequestTime to LastUpdateTime.
                            mFilenameDictionaryUpdateController.mLastUpdateRequestTime =
                                    mFilenameDictionaryUpdateController.mLastUpdateTime;
                        }
                    } else if (mBinaryDictionary == null ||
                            mPerInstanceDictionaryUpdateController.mLastUpdateTime
                                    < mFilenameDictionaryUpdateController.mLastUpdateTime) {
                        // Otherwise, if the local dictionary is older than the shared dictionary,
                        // load the shared dictionary.
                        loadBinaryDictionary();
                    }
                    // If we just loaded the binary dictionary, then mBinaryDictionary is not
                    // up-to-date yet so it's useless to test it right away. Schedule the check
                    // for right after it's loaded instead.
                    getExecutor(mFilename).executePrioritized(new Runnable() {
                        @Override
                        public void run() {
                            if (mBinaryDictionary != null && !(isValidDictionary()
                                    // TODO: remove the check below
                                    && matchesExpectedBinaryDictFormatVersionForThisType(
                                            mBinaryDictionary.getFormatVersion()))) {
                                // Binary dictionary or its format version is not valid. Regenerate
                                // the dictionary file. writeBinaryDictionary will remove the
                                // existing files if appropriate.
                                mFilenameDictionaryUpdateController.mLastUpdateTime = time;
                                writeBinaryDictionary();
                                loadBinaryDictionary();
                            }
                            mPerInstanceDictionaryUpdateController.mLastUpdateTime = time;
                        }
                    });
                } finally {
                    mFilenameDictionaryUpdateController.mProcessingLargeTask.set(false);
                }
            }
        });
    }

    // TODO: cache the file's existence so that we avoid doing a disk access each time.
    private boolean dictionaryFileExists() {
        final File file = new File(mContext.getFilesDir(), mFilename);
        return file.exists();
    }

    /**
     * Generate binary dictionary using DictionaryWriter.
     */
    protected void asyncFlushBinaryDictionary() {
        final Runnable newTask = new Runnable() {
            @Override
            public void run() {
                writeBinaryDictionary();
            }
        };
        final Runnable oldTask = mUnfinishedFlushingTask.getAndSet(newTask);
        getExecutor(mFilename).replaceAndExecute(oldTask, newTask);
    }

    /**
     * For tracking whether the dictionary is out of date and the dictionary is used in a large
     * task. Can be shared across multiple dictionary instances that access the same filename.
     */
    private static class DictionaryUpdateController {
        public volatile long mLastUpdateTime = 0;
        public volatile long mLastUpdateRequestTime = 0;
        public volatile AtomicBoolean mProcessingLargeTask = new AtomicBoolean();

        public boolean isOutOfDate() {
            return (mLastUpdateRequestTime > mLastUpdateTime);
        }
    }

    // TODO: Implement BinaryDictionary.isInDictionary().
    @UsedForTesting
    public boolean isInDictionaryForTests(final String word) {
        final AsyncResultHolder<Boolean> holder = new AsyncResultHolder<Boolean>();
        getExecutor(mFilename).executePrioritized(new Runnable() {
            @Override
            public void run() {
                if (mDictType == Dictionary.TYPE_USER_HISTORY) {
                    holder.set(mBinaryDictionary.isValidWord(word));
                }
            }
        });
        return holder.get(false, TIMEOUT_FOR_READ_OPS_IN_MILLISECONDS);
    }

    @UsedForTesting
    public void shutdownExecutorForTests() {
        getExecutor(mFilename).shutdown();
    }

    @UsedForTesting
    public boolean isTerminatedForTests() {
        return getExecutor(mFilename).isTerminated();
    }

    @UsedForTesting
    protected void runAfterGcForDebug(final Runnable r) {
        getExecutor(mFilename).executePrioritized(new Runnable() {
            @Override
            public void run() {
                try {
                    mBinaryDictionary.flushWithGC();
                    r.run();
                } finally {
                    mFilenameDictionaryUpdateController.mProcessingLargeTask.set(false);
                }
            }
        });
    }
}
