/*
 * Copyright (C) 2016 Ritayan Chakraborty (out386) and Harsh Shandilya (MSF-Jarvis)
 *
 * This file is part of AFH Browser.
 *
 * AFH Browser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AFH Browser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AFH Browser. If not, see <http://www.gnu.org/licenses/>.
 */

package browser.afh.data;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.customtabs.CustomTabsIntent;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.baoyz.widget.PullRefreshLayout;
import com.crashlytics.android.Crashlytics;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.MalformedJsonException;
import com.mikepenz.fastadapter.FastAdapter;
import com.mikepenz.fastadapter.IAdapter;
import com.mikepenz.fastadapter.adapters.GenericItemAdapter;
import com.timehop.stickyheadersrecyclerview.StickyRecyclerHeadersDecoration;
import com.turingtechnologies.materialscrollbar.AlphabetIndicator;
import com.turingtechnologies.materialscrollbar.DateAndTimeIndicator;
import com.turingtechnologies.materialscrollbar.TouchScrollBar;

import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import browser.afh.BuildConfig;
import browser.afh.MainActivity;
import browser.afh.R;
import browser.afh.recycler.DateStickyHeaderAdapter;
import browser.afh.recycler.FileItem;
import browser.afh.recycler.StickyHeaderAdapter;
import browser.afh.tools.Comparators;
import browser.afh.tools.Constants;
import browser.afh.tools.Retrofit.ApiInterface;
import browser.afh.tools.Retrofit.RetroClient;
import browser.afh.tools.Utils;
import browser.afh.types.AfhDevelopers;
import browser.afh.types.AfhFolders;
import browser.afh.types.Files;
import retrofit2.Call;
import retrofit2.Callback;

public class FindFiles {
    private final PullRefreshLayout pullRefreshLayout;
    private final String TAG = Constants.TAG;
    private final SimpleDateFormat sdf;
    private final View rootView;
    private final GenericItemAdapter<Files, FileItem> mFilesAdapter;
    final private List<Files> filesD;
    private String savedID;
    private boolean sortByDate;
    private boolean isPrintFirstRun = true;
    private ApiInterface retroApi;
    private FindDevices.AppbarScroll appbarScroll;
    private Context mContext;
    private Intent snackbarIntent = new Intent(Constants.INTENT_SNACKBAR);
    private RecyclerView mRecyclerView;
    private Handler mDisplayHandler = new Handler();
    private ClearRunnable mRunnable = new ClearRunnable();
    private LinearLayoutManager layoutManager;
    private StickyHeaderAdapter stickyHeaderAdapter;
    private StickyRecyclerHeadersDecoration stickyRecyclerHeadersDecoration;
    private DateStickyHeaderAdapter dateStickyHeaderAdapter;
    private StickyRecyclerHeadersDecoration dateStickyRecyclerHeadersDecoration;
    private AtomicInteger filesReqCounter = new AtomicInteger(0);
    private AtomicInteger maxProgressCounter = new AtomicInteger(0);
    private boolean usingCache = false;
    private boolean isCacheRequested = true;

    public FindFiles(final View rootView, MainActivity activity) {
        this.rootView = rootView;
        mContext = activity;
        appbarScroll = activity;
        sdf = new SimpleDateFormat("yyyy/MM/dd, HH:mm", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getDefault());
        retroApi = RetroClient.getApi(rootView.getContext(), true);
        mRecyclerView = rootView.findViewById(R.id.recycler);
        CheckBox sortCB = rootView.findViewById(R.id.sortCB);
        Utils.tintCheckbox(sortCB, mContext);

        layoutManager = new LinearLayoutManager(rootView.getContext());
        FastAdapter<FileItem> fastAdapter = new FastAdapter<>();
        mFilesAdapter = new GenericItemAdapter<>(FileItem.class, Files.class);
        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());
        fastAdapter.withSelectable(true);

        filesD = Collections.synchronizedList(new LinkedList<Files>());

        pullRefreshLayout = rootView.findViewById(R.id.swipeRefreshLayout);
        pullRefreshLayout.setOnRefreshListener(() -> {
                    filesD.clear();
                    mFilesAdapter.clear();
                    maxProgressCounter.set(0);
                    filesReqCounter.set(0);
                    updateProgress(maxProgressCounter.get(), filesReqCounter.get());
                    retroApi = RetroClient.getApi(rootView.getContext(), false);
                    isCacheRequested = false;
                    setHeaderText(0);
                    start(savedID);
                }
        );

        /* Needed to prevent PullRefreshLayout from refreshing every time someone
         * tries to scroll up. The fast scrollbar needs RecyclerView to be a child
         * of a RelativeLayout. PullRefreshLayout needs a scrollable child. That makes this
         * workaround necessary.
         */
        mRecyclerView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                int scroll = mRecyclerView.computeVerticalScrollOffset();
                if (scroll == 0) {
                    appbarScroll.expand();
                    pullRefreshLayout.setEnabled(true);
                } else {
                    pullRefreshLayout.setEnabled(false);
                    if (scroll > 50) {
                        appbarScroll.collapse();
                    }
                }
            }
        });

        fastAdapter.withOnClickListener((View v, IAdapter<FileItem> adapter, FileItem item, int position) -> {
                    Files p = item.getModel();
                    new MaterialDialog.Builder(activity)
                            .title(p.name)
                            .content(String.format(activity.getString(R.string.file_dialog_content), p.file_size, p.upload_date, p.screenname, p.downloads))
                            .positiveText(R.string.file_dialog_positive_button_label)
                            .neutralText(R.string.file_dialog_neutral_button_label)
                            .onPositive((@NonNull MaterialDialog dialog, @NonNull DialogAction which) -> {
                                        try {
                                            customTab(p.url);
                                        } catch (ActivityNotFoundException exc) {
                                            new MaterialDialog.Builder(activity)
                                                    .title(R.string.no_browser_dialog_title)
                                                    .content(R.string.no_browser_dialog_content)
                                                    .neutralText(R.string.no_browser_dialog_assert)
                                                    .onNeutral((dialog1, which1) -> dialog1.dismiss())
                                                    .show();
                                        }
                                        dialog.dismiss();
                                    }
                            )
                            .onNeutral((@NonNull MaterialDialog dialog, @NonNull DialogAction which) -> dialog.dismiss()
                            )
                            .show();
                    return true;
                }
        );

        fastAdapter.withOnLongClickListener((View v, IAdapter<FileItem> adapter, FileItem item, int position) -> {
                    try {
                        customTab(item.getModel().url);
                    } catch (ActivityNotFoundException exc) {
                        new MaterialDialog.Builder(activity)
                                .title(R.string.no_browser_dialog_title)
                                .content(R.string.no_browser_dialog_content)
                                .neutralText(R.string.no_browser_dialog_assert)
                                .onNeutral((dialog, which) -> dialog.dismiss())
                                .show();
                    }
                    return true;
                }
        );

        stickyHeaderAdapter = new StickyHeaderAdapter();
        stickyRecyclerHeadersDecoration = new StickyRecyclerHeadersDecoration(stickyHeaderAdapter);
        dateStickyHeaderAdapter = new DateStickyHeaderAdapter();
        dateStickyRecyclerHeadersDecoration = new StickyRecyclerHeadersDecoration(dateStickyHeaderAdapter);

        TouchScrollBar materialScrollBar = new TouchScrollBar(rootView.getContext(), mRecyclerView, true);
        materialScrollBar.setHandleColour(Utils.getPrefsColour(2, mContext));
        mRecyclerView.addItemDecoration(stickyRecyclerHeadersDecoration);
        mRecyclerView.setAdapter(stickyHeaderAdapter.wrap(mFilesAdapter.wrap(fastAdapter)));
        materialScrollBar.addIndicator(new AlphabetIndicator(mContext), true);

        sortCB.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
                    sortByDate = isChecked;
                    if (!isChecked) {
                        materialScrollBar.removeIndicator();
                        mRecyclerView.setAdapter(stickyHeaderAdapter.wrap(mFilesAdapter.wrap(fastAdapter)));
                        mRecyclerView.removeItemDecoration(dateStickyRecyclerHeadersDecoration);
                        mRecyclerView.addItemDecoration(stickyRecyclerHeadersDecoration);
                        materialScrollBar.addIndicator(new AlphabetIndicator(mContext), true);
                    } else {
                        materialScrollBar.removeIndicator();
                        mRecyclerView.setAdapter(dateStickyHeaderAdapter.wrap(mFilesAdapter.wrap(fastAdapter)));
                        mRecyclerView.removeItemDecoration(stickyRecyclerHeadersDecoration);
                        mRecyclerView.addItemDecoration(dateStickyRecyclerHeadersDecoration);
                        materialScrollBar.addIndicator(new DateAndTimeIndicator(mContext,
                                false, true, true, false), true);
                    }
                    print(true);
                }
        );
    }

    public void start(final String did) {
        savedID = did;
        pullRefreshLayout.setRefreshing(true);
        Call<AfhDevelopers> call = retroApi.getDevelopers("developers", did, 100);
        incFreqCounter();
        call.enqueue(new Callback<AfhDevelopers>() {
            @Override
            public void onResponse(Call<AfhDevelopers> call, retrofit2.Response<AfhDevelopers> response) {
                usingCache = isFromCache(response);
                List<AfhDevelopers.Developer> fid;
                filesReqCounter.decrementAndGet();
                if (response.isSuccessful()) {
                    try {
                        fid = response.body().data;
                    } catch (Exception e) {
                        //try-catch needed to log with Fabric
                        Crashlytics.log("did : " + did);
                        Crashlytics.logException(e);
                        Crashlytics.log("did : " + did);

                        // Files might exist, we just didn't get a list of them.
                        // Should probably retry, though.
                        showSnackbar(R.string.files_list_no_files_text);
                        return;
                    }
                    if (fid != null && fid.size() > 0) {
                        queryDirs(fid);
                    } else {
                        pullRefreshLayout.setRefreshing(false);
                        showSnackbar(R.string.files_list_no_files_text);
                    }
                } else if (response.code() == 502) {
                    // Keeps happening for some devices, suspected for files, too. Re-queuing probably won't help, though.
                    // Let's get to know if it happens to files, too.
                    try {
                        throw new IllegalArgumentException();
                    } catch (Exception e) {
                        // Have to catch Exception, Crashlytics doesn't seem to want to know specifics.
                        if (!BuildConfig.DEBUG) {
                            Crashlytics.logException(e);
                            Crashlytics.log("did : " + did);
                        }
                    }
                    incFreqCounter();
                    call.clone().enqueue(this);
                    showSnackbar(R.string.files_list_502_text);
                } else {
                    try {
                        // Crashlytics sure loves getting stuff thrown at it.
                        throw new IllegalArgumentException();
                    } catch (Exception e) {
                        // Have to catch Exception, Crashlytics doesn't seem to want to know specifics.
                        Crashlytics.logException(e);
                        Crashlytics.log("Error code : " + response.code());
                        Crashlytics.log("did : " + did);
                    }
                }
            }

            @Override
            public void onFailure(Call<AfhDevelopers> call, Throwable t) {
                filesReqCounter.decrementAndGet();
                maxProgressCounter.decrementAndGet();
                if (t instanceof UnknownHostException) {
                    showSnackbar(R.string.files_list_no_cache_text);
                    pullRefreshLayout.setRefreshing(false);
                    return;
                }
                if (!(t instanceof JsonSyntaxException)) {
                    if (!t.toString().contains("Canceled")) {
                        if ((t instanceof MalformedJsonException)) {
                            pullRefreshLayout.setRefreshing(false);
                            showSnackbar(R.string.internal_error);
                        } else {
                            if (BuildConfig.DEBUG)
                                Log.i(TAG, "onErrorResponse dirs " + t.toString() + " on "
                                        + call.request().url().queryParameter("did"));
                            incFreqCounter();
                            call.clone().enqueue(this);
                        }
                    }
                } else {
                    // As the list on devs is only fetched once, if it doesn't exist, then there are no files
                    // API causes this sometimes if no data exists
                    showSnackbar(R.string.files_list_no_files_text);
                    pullRefreshLayout.setRefreshing(false);
                }
            }
        });
    }

    private void queryDirs(final List<AfhDevelopers.Developer> did) {

        for (final AfhDevelopers.Developer url : did) {
            Call<AfhFolders> call = retroApi.getFolderContents("folder", url.flid, 100);
            incFreqCounter();
            call.enqueue(new Callback<AfhFolders>() {
                @Override
                public void onResponse(Call<AfhFolders> call, retrofit2.Response<AfhFolders> response) {
                    usingCache = isFromCache(response);
                    List<Files> filesList = null;
                    List<AfhDevelopers.Developer> foldersList = null;
                    filesReqCounter.decrementAndGet();
                    if (response.isSuccessful()) {
                        try {
                            filesList = response.body().data.files;
                            foldersList = response.body().data.folders;
                        } catch (Exception e) {
                            Crashlytics.logException(e);
                            Crashlytics.log("flid : " + url.flid);
                        }

                        if (filesList != null && filesList.size() > 0) {

                            for (Files file : filesList) {
                                file.screenname = url.screenname;

                                try {
                                    file.file_size = Utils.sizeFormat(Integer.parseInt(file.file_size));
                                    file.upload_date_long = Long.parseLong(file.upload_date) * 1000;
                                    file.upload_date = sdf.format(new Date(file.upload_date_long));
                                } catch (Exception e) {
                                    Crashlytics.logException(e);
                                    Crashlytics.log("flid : " + url.flid);
                                    Crashlytics.log("name : " + file.name);
                                    Crashlytics.log("file_size : " + file.file_size);
                                    Crashlytics.log("upload_date : " + file.upload_date);
                                }

                                if (BuildConfig.PLAY_COMPATIBLE) {
                                    if (file.name.endsWith(".apk") || file.name.endsWith(".APK")) {
                                        // Filtering out APK files as Google Play hates them
                                        // But but but...!
                                        continue;
                                    }
                                }

                                if (BuildConfig.PLAY_COMPATIBLE) {
                                /* Attempting to filter out private files, which typically get less than 10 downloads
                                * This will also hide all newly uploaded files, sorry.
                                * Getting complaints of pissed off devs having their private builds passed around.
                                */
                                    if (file.downloads < 10)
                                        continue;
                                }

                                filesD.add(file);
                            }
                            print(false);
                        }

                        if (foldersList != null && foldersList.size() > 0) {
                            for (AfhDevelopers.Developer folder : foldersList) {
                                folder.screenname = url.screenname;
                            }
                            queryDirs(foldersList);
                        }
                    } else if (response.code() == 502) {
                        // Keeps happening for some devices, suspected for files, too. Re-queuing probably won't help, though.
                        // Let's get to know if it happens to files, too.
                        try {
                            throw new IllegalArgumentException();
                        } catch (Exception e) {
                            // Have to catch Exception, Crashlytics doesn't seem to want to know specifics.
                            if (!BuildConfig.DEBUG) {
                                Crashlytics.logException(e);
                                Crashlytics.log("flid : " + url.flid);
                            }
                        }
                        incFreqCounter();
                        call.clone().enqueue(this);
                    } else {
                        try {
                            // Crashlytics sure loves getting stuff thrown at it.
                            throw new IllegalArgumentException();
                        } catch (Exception e) {
                            // Have to catch Exception, Crashlytics doesn't seem to want to know specifics.
                            Crashlytics.logException(e);
                            Crashlytics.log("Error code : " + response.code());
                            Crashlytics.log("flid : " + url.flid);
                        }
                    }
                }

                @Override
                public void onFailure(Call<AfhFolders> call, Throwable t) {
                    filesReqCounter.decrementAndGet();
                    maxProgressCounter.decrementAndGet();
                    // AfhFolders.DATA will be an Object, but if it is empty, it'll be an array
                    if (!(t instanceof UnknownHostException)
                            && !(t instanceof IllegalStateException)
                            && !(t instanceof JsonSyntaxException)
                            && !t.toString().contains("Canceled")) {
                        pullRefreshLayout.setRefreshing(false);
                        if (t instanceof MalformedJsonException)
                            showSnackbar(R.string.internal_error);
                        else {
                            if (BuildConfig.DEBUG)
                                Log.i(TAG, "onErrorResponse dirs " + t.toString() + " on "
                                        + call.request().url().queryParameter("flid"));
                            incFreqCounter();
                            call.clone().enqueue(this);
                        }
                    } else if (t instanceof UnknownHostException) {
                        pullRefreshLayout.setRefreshing(false);
                        showSnackbar(R.string.files_list_no_cache_text);
                    }
                }
            });
        }

    }

    private synchronized void print(boolean isInstant) {
        final int INTERVAL = isInstant ? 0 : 1000;
        final int SLOP = 250;

        // isPrintFirstRun ensures that files are displayed at least once, even if all calls are made in < INTERVAL ms of  mRunnable instantiation
        if (isPrintFirstRun
                || System.currentTimeMillis() - mRunnable.time >= INTERVAL - SLOP) {
            isPrintFirstRun = false;
            mDisplayHandler.removeCallbacks(mRunnable);
            mRunnable = new ClearRunnable();
            // Needed to prevent 500+ rapid calls to reload while loading from the cache
            mDisplayHandler.postDelayed(mRunnable, INTERVAL);
        }
    }

    public void reset() {
        RetroClient.cancelRequests();
        retroApi = RetroClient.getApi(rootView.getContext(), true);
        filesD.clear();
        mFilesAdapter.clear();
        setHeaderText(0);
        maxProgressCounter.set(0);
        filesReqCounter.set(0);
        mDisplayHandler.removeCallbacks(mRunnable);
        isPrintFirstRun = true;
        usingCache = false;
        isCacheRequested = true;
        updateProgress(maxProgressCounter.get(), filesReqCounter.get());
    }

    private void showSnackbar(int messageRes) {
        snackbarIntent.removeExtra(Constants.EXTRA_SNACKBAR_MESSAGE);
        snackbarIntent.putExtra(Constants.EXTRA_SNACKBAR_MESSAGE, messageRes);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(snackbarIntent);
    }

    public List<Files> getFiles() {
        return filesD;
    }

    public void setList(List<Files> list) {
        filesD.clear();
        filesD.addAll(list);
        mFilesAdapter.clear();
        setHeaderText(0);
        // So setHeaderText knows to not say that files are being loaded
        maxProgressCounter.set(1);
        filesReqCounter.set(0);
        updateProgress(maxProgressCounter.get(), filesReqCounter.get());
        mFilesAdapter.addModel(list);
        print(true);
    }

    private void customTab(String Url) {
        CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
        builder.setShowTitle(true);
        CustomTabsIntent customTabsIntent = builder.build();
        customTabsIntent.launchUrl(mContext, Uri.parse(Url));
    }

    private void incFreqCounter() {
        // Yes, 2 Atomics are a bad idea
        maxProgressCounter.addAndGet(1);
        filesReqCounter.addAndGet(1);
    }

    private void updateProgress(int max, int now) {
        int progressNow = max - now;
        appbarScroll.setProgress(progressNow, max);
    }

    private void setHeaderText(int numberOfFiles) {
        String text;
        int lettersBeforeNum;
        if (usingCache) {
            text = rootView.getResources().getString(R.string.num_files_cached);
            lettersBeforeNum = 21;
        } else {
            // As the 2nd condition will be true only when files have just started loading
            if (filesReqCounter.get() > 0 || (filesReqCounter.get() == 0 && maxProgressCounter.get() == 0))
                text = rootView.getResources().getString(R.string.num_files_current_loading);
            else
                text = rootView.getResources().getString(R.string.num_files_current_loaded);
            lettersBeforeNum = 14;
        }
        appbarScroll.setText(Utils.generateSpannable(text, numberOfFiles, lettersBeforeNum));
    }

    /**
     * Not thread safe, but assuming that if one response in a sequence is from the cache, others will be, too.
     * This won't be true in case loading was previously canceled, then some responses will be live, others will be cached.
     */
    private boolean isFromCache(retrofit2.Response<?> response) {
        return response != null && response.raw().cacheResponse() != null && isCacheRequested;
    }

    private class ClearRunnable implements Runnable {
        long time = System.currentTimeMillis();

        @Override
        public void run() {
            if (sortByDate) {
                synchronized (filesD) {
                    Collections.sort(filesD, Comparators.byUploadDate);
                }
            } else {
                synchronized (filesD) {
                    Collections.sort(filesD, Comparators.byFileName);
                }
            }
            if (pullRefreshLayout != null)
                pullRefreshLayout.setRefreshing(false);
            int position = layoutManager.findFirstVisibleItemPosition();
            mFilesAdapter.clear();
            mFilesAdapter.addModel(filesD);
            mRecyclerView.scrollToPosition(position);
            int numberOfFiles = mFilesAdapter.getModels().size();

            setHeaderText(numberOfFiles);
            updateProgress(maxProgressCounter.get(), filesReqCounter.get());
        }
    }
}
