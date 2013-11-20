
package com.trovebox.android.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.holoeverywhere.LayoutInflater;
import org.holoeverywhere.app.Activity;
import org.holoeverywhere.widget.Switch;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.GridView;
import android.widget.ImageView;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.trovebox.android.app.bitmapfun.util.ImageCache;
import com.trovebox.android.app.bitmapfun.util.ImageFileSystemFetcher;
import com.trovebox.android.app.bitmapfun.util.ImageResizer;
import com.trovebox.android.app.bitmapfun.util.ImageWorker.ImageWorkerAdapter;
import com.trovebox.android.app.common.CommonRefreshableFragmentWithImageWorker;
import com.trovebox.android.app.net.account.AccountLimitUtils;
import com.trovebox.android.app.provider.UploadsProviderAccessor;
import com.trovebox.android.app.util.CommonUtils;
import com.trovebox.android.app.util.GuiUtils;
import com.trovebox.android.app.util.LoadingControl;
import com.trovebox.android.app.util.TrackerUtils;
import com.trovebox.android.app.util.concurrent.AsyncTaskEx;

public class SyncImageSelectionFragment extends CommonRefreshableFragmentWithImageWorker
{
    public static final String TAG = SyncImageSelectionFragment.class.getSimpleName();
    public static final String SELECTED_IMAGES = "SyncImageSelectionFragmentSelectedImages";
    public static final String IMAGE_WORKER_ADAPTER = "SyncImageSelectionFragmentAdapter";

    private LoadingControl loadingControl;
    private CustomImageAdapter mAdapter;
    private int mImageThumbSize;
    private int mImageThumbSpacing;
    private int mImageThumbBorder;
    private GridView photosGrid;
    ViewTreeObserver.OnGlobalLayoutListener photosGridListener;
    NextStepFlow nextStepFlow;
    InitTask initTask = null;
    Switch stateSwitch;
    CustomImageWorkerAdapter customImageWorkerAdapter;
    SelectionController selectionController;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        mImageThumbSize = getResources().getDimensionPixelSize(
                R.dimen.image_thumbnail_size);
        mImageThumbSpacing = getResources().getDimensionPixelSize(
                R.dimen.image_thumbnail_spacing);
        mImageThumbBorder = getResources().getDimensionPixelSize(
                R.dimen.image_thumbnail_border);

        customImageWorkerAdapter = CommonUtils.getParcelableFromBundleIfNotNull(
                IMAGE_WORKER_ADAPTER, savedInstanceState);
        selectionController = CommonUtils.getParcelableFromBundleIfNotNull(SELECTED_IMAGES,
                savedInstanceState);
        if (selectionController == null)
        {
            selectionController = new SelectionController();
        }
        if (customImageWorkerAdapter != null)
        {
            mImageWorker.setAdapter(customImageWorkerAdapter);
        }
        mAdapter = new CustomImageAdapter(getActivity(), (ImageResizer) mImageWorker,
                selectionController);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.sync_image_selection, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId())
        {
            case R.id.menu_select_all: {
                TrackerUtils
                        .trackOptionsMenuClickEvent("menu_select_all",
                                SyncImageSelectionFragment.this);
                selectAll();
                return true;
            }
            case R.id.menu_select_none: {
                TrackerUtils.trackOptionsMenuClickEvent("menu_select_none",
                        SyncImageSelectionFragment.this);
                selectNone();
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState)
    {
        super.onCreateView(inflater, container, savedInstanceState);
        View v = inflater.inflate(R.layout.fragment_sync_select_photos,
                container, false);
        return v;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        init(view);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (mAdapter != null)
        {
            mAdapter.mItemHeight = 0;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(SELECTED_IMAGES, selectionController);
        outState.putParcelable(IMAGE_WORKER_ADAPTER, customImageWorkerAdapter);
    }

    public void init(View v)
    {
        photosGrid = (GridView) v.findViewById(R.id.grid_photos);
        photosGrid.setAdapter(new DummyImageAdapter());

        // This listener is used to get the final width of the GridView and then
        // calculate the
        // number of columns and the width of each column. The width of each
        // column is variable
        // as the GridView has stretchMode=columnWidth. The column width is used
        // to set the height
        // of each view so we get nice square thumbnails.
        photosGridListener = new ViewTreeObserver.OnGlobalLayoutListener()
        {
            @Override
            public void onGlobalLayout()
            {
                if (mAdapter.mItemHeight == 0)
                {
                    final int numColumns = (int) Math.floor(
                            photosGrid.getWidth()
                                    / (mImageThumbSize
                                            + mImageThumbSpacing + mImageThumbBorder));
                    if (numColumns > 0)
                    {
                        final int columnWidth =
                                (photosGrid.getWidth() / numColumns)
                                        - mImageThumbSpacing;
                        mAdapter.setItemHeight(columnWidth, columnWidth
                                - 2 * mImageThumbBorder);
                        if (BuildConfig.DEBUG)
                        {
                            CommonUtils.debug(TAG,
                                    "onCreateView - numColumns set to "
                                            + numColumns);
                        }
                    }
                }
            }
        };
        photosGrid.getViewTreeObserver().addOnGlobalLayoutListener(photosGridListener);
        final Button nextStepBtn = (Button) v.findViewById(R.id.nextBtn);
        nextStepBtn.setOnClickListener(new OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                TrackerUtils.trackButtonClickEvent("nextBtn", SyncImageSelectionFragment.this);
                if (isDataLoaded())
                {
                    if (selectionController.hasSelected())
                    {
                        int selectedCount = getSelectedCount();
                        nextStepBtn.setEnabled(false);
                        AccountLimitUtils.checkQuotaPerUploadAvailableAndRunAsync(new Runnable() {
                            @Override
                            public void run() {
                                CommonUtils.debug(TAG, "Upload limit check passed");
                                TrackerUtils.trackLimitEvent("sync_move_to_second_step", "success");
                                if (nextStepFlow != null)
                                {
                                    nextStepFlow.activateNextStep();
                                }
                                nextStepBtn.setEnabled(true);
                            }
                        }, new Runnable() {

                            @Override
                            public void run() {
                                CommonUtils.debug(TAG, "Upload limit check failed");
                                TrackerUtils.trackLimitEvent("sync_move_to_second_step", "fail");
                                nextStepBtn.setEnabled(true);
                            }
                        }, selectedCount, loadingControl);
                    } else
                    {
                        GuiUtils.alert(R.string.sync_please_pick_at_least_one_photo);
                    }
                }
            }
        });
        stateSwitch = (Switch) v.findViewById(R.id.uploaded_state_switch);
        stateSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener()
        {

            @Override
            public void onCheckedChanged(CompoundButton buttonView,
                    boolean isChecked)
            {
                switchUploadState(isChecked);
            }
        });
        if (isDataLoaded())
        {
            photosGrid.setAdapter(mAdapter);
        } else
        {
            if (initTask == null)
            {
                refresh(v);
            }
        }
    }

    @Override
    protected void initImageWorker()
    {
        mImageWorker = new CustomImageFileSystemFetcher(getActivity(),
                loadingControl,
                mImageThumbSize);
        mImageWorker.setLoadingImage(R.drawable.empty_photo);

        mImageWorker.setImageCache(ImageCache.findOrCreateCache(getActivity(),
                ImageCache.LOCAL_THUMBS_CACHE_DIR, 1500, true, false));
    }

    protected void switchUploadState(boolean isChecked)
    {
        if (isDataLoaded())
        {
            customImageWorkerAdapter.setFiltered(!isChecked);
            mAdapter.notifyDataSetChanged();
        }
    }

    protected void selectAll()
    {
        if (isDataLoaded())
        {
            for (int i = 0, size = customImageWorkerAdapter.getSize(); i < size; i++)
            {
                ImageData imageData = (ImageData) customImageWorkerAdapter.getItem(i);
                if (imageData != null &&
                        !selectionController.isSelected(imageData.id)
                        && !customImageWorkerAdapter.isProcessedValue(imageData))
                {
                    selectionController.addToSelected(imageData.id);
                }
            }
            mAdapter.notifyDataSetChanged();
        }
    }

    protected void selectNone()
    {
        if (isDataLoaded())
        {
            selectionController.clearSelection();
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onAttach(Activity activity)
    {
        super.onAttach(activity);
        loadingControl = ((LoadingControl) activity);

    }

    @Override
    public void refresh()
    {
        refresh(getView());
    }

    void refresh(View v)
    {
        if (initTask == null)
        {
            initTask = new InitTask();
            initTask.execute();
        }
    }

    public boolean isDataLoaded()
    {
        return customImageWorkerAdapter != null;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (isDataLoaded())
        {
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onDestroyView()
    {
        super.onDestroyView();
        if (initTask != null)
        {
            initTask.cancel(true);
        }
        GuiUtils.removeGlobalOnLayoutListener(photosGrid, photosGridListener);

    }

    public void clear()
    {
        selectionController.clearSelection();
    }

    /**
     * Get the selected images count
     * 
     * @return
     */
    public int getSelectedCount() {
        if (selectionController == null)
        {
            return 0;
        }
        return selectionController.selectedIds.size();
    }

    public ArrayList<String> getSelectedFileNames()
    {
        long start = System.currentTimeMillis();
        ArrayList<String> result = new ArrayList<String>();
        if (customImageWorkerAdapter == null)
        {
            return result;
        }
        for (int i = 0, size = customImageWorkerAdapter.getSize(); i < size; i++)
        {
            ImageData imageData = (ImageData) customImageWorkerAdapter.getItem(i);
            if (imageData != null && selectionController.isSelected(imageData.id))
            {
                result.add(imageData.data);
            }
        }
        TrackerUtils.trackDataProcessingTiming(System.currentTimeMillis() - start,
                "getSelectedFileNames",
                TAG);
        return result;
    }

    public NextStepFlow getNextStepFlow()
    {
        return nextStepFlow;
    }

    public void setNextStepFlow(NextStepFlow nextStepFlow)
    {
        this.nextStepFlow = nextStepFlow;
    }

    static interface NextStepFlow
    {
        void activateNextStep();
    }

    public void addProcessedValues(List<String> values)
    {
        if (isDataLoaded())
        {
            customImageWorkerAdapter.addProcessedValues(values);
            mAdapter.notifyDataSetChanged();
        }
    }

    public static class ImageData implements Parcelable
    {
        public long id;
        public String data;
        public String folder;

        public ImageData(long id, String data)
        {
            super();
            this.id = id;
            this.data = data;
            folder = getFolderFromPath(data);
        }

        /**
         * Get the parent folder name for the specified path
         * 
         * @param path
         * @return
         */
        public String getFolderFromPath(String path)
        {
            if (path == null)
            {
                return null;
            }
            int p = path.lastIndexOf("/");
            String result = "";
            if (p > 0)
            {
                int p2 = path.lastIndexOf("/", p - 1);
                if (p2 != -1)
                {
                    result = path.substring(p2 + 1, p);
                }
            }
            CommonUtils.debug(TAG, "getFolderFromPath: fileName '%1$s; folderName '%2$s'", path,
                    result);
            return result;
        }

        @Override
        public String toString()
        {
            return data;
        }

        /*****************************
         * PARCELABLE IMPLEMENTATION *
         *****************************/
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeLong(id);
            out.writeString(data);
            out.writeString(folder);
        }

        public static final Parcelable.Creator<ImageData> CREATOR = new Parcelable.Creator<ImageData>() {
            @Override
            public ImageData createFromParcel(Parcel in) {
                return new ImageData(in);
            }

            @Override
            public ImageData[] newArray(int size) {
                return new ImageData[size];
            }
        };

        private ImageData(Parcel in) {
            id = in.readLong();
            data = in.readString();
            folder = in.readString();
        }
    }

    private class InitTask extends
            AsyncTaskEx<Void, Void, Boolean>
    {
        CustomImageWorkerAdapter adapter;

        @Override
        protected Boolean doInBackground(Void... params)
        {
            try
            {
                adapter = new CustomImageWorkerAdapter();
                return true;
            } catch (Exception e)
            {
                GuiUtils.error(TAG,
                        null,
                        e);
            }
            return false;
        }

        @Override
        protected void onPreExecute()
        {
            super.onPreExecute();
            loadingControl.startLoading();
            photosGrid.setAdapter(new DummyImageAdapter());
            customImageWorkerAdapter = null;
            mImageWorker.setAdapter(null);
            selectionController.clearSelection();
        }

        @Override
        protected void onCancelled()
        {
            super.onCancelled();
            loadingControl.stopLoading();
            initTask = null;
        }

        @Override
        protected void onPostExecute(Boolean result)
        {
            super.onPostExecute(result);
            loadingControl.stopLoading();
            initTask = null;
            if (!isCancelled())
            {
                adapter.setFiltered(!stateSwitch.isChecked());
                customImageWorkerAdapter = adapter;
                mImageWorker.setAdapter(adapter);
                if (photosGrid != null)
                {
                    selectionController.clearSelection();
                    photosGrid.setAdapter(mAdapter);
                }
            }
        }

    }

    public static class SelectionController implements Parcelable
    {
        Set<Long> selectedIds = new TreeSet<Long>();

        public SelectionController()
        {

        }

        public boolean isSelected(final long id)
        {
            return selectedIds.contains(id);
        }

        public void addToSelected(final long id)
        {
            selectedIds.add(id);
        }

        public void removeFromSelected(final long id)
        {
            selectedIds.remove(id);
        }

        void clearSelection()
        {
            selectedIds.clear();
        }

        boolean hasSelected()
        {
            return !selectedIds.isEmpty();
        }

        /*****************************
         * PARCELABLE IMPLEMENTATION *
         *****************************/
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(selectedIds.size());
            for (Long id : selectedIds)
            {
                out.writeLong(id);
            }
        }

        public static final Parcelable.Creator<SelectionController> CREATOR = new Parcelable.Creator<SelectionController>() {
            @Override
            public SelectionController createFromParcel(Parcel in) {
                return new SelectionController(in);
            }

            @Override
            public SelectionController[] newArray(int size) {
                return new SelectionController[size];
            }
        };

        private SelectionController(Parcel in) {
            int size = in.readInt();
            for (int i = 0; i < size; i++)
            {
                selectedIds.add(in.readLong());
            }
        }
    }

    private class DummyImageAdapter extends BaseAdapter
    {

        @Override
        public int getCount() {
            return 0;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return null;
        }

    }

    private class CustomImageAdapter extends ImageAdapter {
        SelectionController selectionController;

        public CustomImageAdapter(Context context, ImageResizer imageWorker,
                SelectionController selectionController)
        {
            super(context, imageWorker);
            this.selectionController = selectionController;
        }

        @Override
        public View getViewAdditional(int position, View convertView,
                ViewGroup container)
        {
            // Now handle the main ImageView thumbnails
            final ViewHolder holder;
            if (convertView == null)
            { // if it's not recycled, instantiate and initialize
                convertView = layoutInflater.inflate(
                        R.layout.item_sync_image, null);
                convertView.setLayoutParams(mImageViewLayoutParams);
                holder = new ViewHolder();
                holder.selectedOverlay = convertView
                        .findViewById(R.id.selection_overlay);
                holder.uploadedOverlay = convertView
                        .findViewById(R.id.uploaded_overlay);
                holder.imageContainer = convertView.findViewById(R.id.imageContainer);
                holder.imageView = (ImageView) convertView.findViewById(R.id.image);
                convertView.setTag(holder);
            } else
            { // Otherwise re-use the converted view
                holder = (ViewHolder) convertView.getTag();
            }

            // Check the height matches our calculated column width
            if (convertView.getLayoutParams().height != mItemHeight)
            {
                convertView.setLayoutParams(mImageViewLayoutParams);
            }
            ImageData value = (ImageData) getItem(position);
            final long id = value.id;

            holder.selectedOverlay.setVisibility(selectionController.isSelected(id) ?
                    View.VISIBLE : View.INVISIBLE);
            boolean isProcessed = customImageWorkerAdapter
                    .isProcessedValue(value);
            holder.uploadedOverlay.setVisibility(isProcessed ?
                    View.VISIBLE : View.INVISIBLE);
            if (isProcessed)
            {
                holder.imageContainer.setOnClickListener(null);
            } else
            {
                holder.imageContainer.setOnClickListener(new OnClickListener()
                {
                    @Override
                    public void onClick(View v)
                    {
                        TrackerUtils.trackButtonClickEvent("imageContainer",
                                SyncImageSelectionFragment.this);
                        boolean selected = selectionController.isSelected(id);
                        if (selected)
                        {
                            selectionController.removeFromSelected(id);
                        } else
                        {
                            selectionController.addToSelected(id);
                        }
                        holder.selectedOverlay.setVisibility(selectionController.isSelected(id) ?
                                View.VISIBLE : View.INVISIBLE);
                    }

                });
            }
            // Finally load the image asynchronously into the ImageView, this
            // also takes care of
            // setting a placeholder image while the background thread runs
            mImageWorker.loadImage(position, holder.imageView);
            return convertView;
        }

        protected class ViewHolder
        {
            View selectedOverlay;
            View uploadedOverlay;
            View imageContainer;
            ImageView imageView;
        }
    }

    /**
     * The main adapter that backs the GridView. This is fairly standard except
     * the number of columns in the GridView is used to create a fake top row of
     * empty views as we use a transparent ActionBar and don't want the real top
     * row of images to start off covered by it.
     */
    private static class ImageAdapter extends BaseAdapter {

        protected final Context mContext;
        protected int mItemHeight = 0;
        protected GridView.LayoutParams mImageViewLayoutParams;
        private ImageResizer mImageWorker;
        LayoutInflater layoutInflater;

        public ImageAdapter(Context context, ImageResizer imageWorker)
        {
            super();
            mContext = context;
            this.mImageWorker = imageWorker;
            this.layoutInflater = LayoutInflater.from(context);
            mImageViewLayoutParams = new GridView.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        }

        @Override
        public int getCount()
        {
            return mImageWorker.getAdapter().getSize();
        }

        @Override
        public Object getItem(int position)
        {
            return mImageWorker.getAdapter().getItem(position);
        }

        @Override
        public long getItemId(int position)
        {
            return position;
        }

        @Override
        public boolean hasStableIds()
        {
            return true;
        }

        @Override
        public final View getView(int position, View convertView,
                ViewGroup container)
        {
            return getViewAdditional(position, convertView, container);
        }

        public View getViewAdditional(int position, View convertView,
                ViewGroup container)
        {
            // Now handle the main ImageView thumbnails
            ImageView imageView;
            if (convertView == null)
            { // if it's not recycled, instantiate and initialize
                imageView = new ImageView(mContext);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.setLayoutParams(mImageViewLayoutParams);
            } else
            { // Otherwise re-use the converted view
                imageView = (ImageView) convertView;
            }

            // Check the height matches our calculated column width
            if (imageView.getLayoutParams().height != mItemHeight)
            {
                imageView.setLayoutParams(mImageViewLayoutParams);
            }
            // Finally load the image asynchronously into the ImageView, this
            // also takes care of
            // setting a placeholder image while the background thread runs
            mImageWorker.loadImage(position, imageView);
            return imageView;
        }

        /**
         * Sets the item height. Useful for when we know the column width so the
         * height can be set to match.
         * 
         * @param height
         */
        public void setItemHeight(int height, int imageHeight)
        {
            if (height == mItemHeight)
            {
                return;
            }
            mItemHeight = height;
            mImageViewLayoutParams =
                    new GridView.LayoutParams(LayoutParams.MATCH_PARENT,
                            mItemHeight);
            mImageWorker.setImageSize(imageHeight);
            notifyDataSetChanged();
        }
    }

    private class CustomImageFileSystemFetcher extends ImageFileSystemFetcher
    {
        public CustomImageFileSystemFetcher(Context context,
                LoadingControl loadingControl, int imageSize)
        {
            super(context, loadingControl, imageSize);
        }

        public CustomImageFileSystemFetcher(Context context,
                LoadingControl loadingControl, int imageWidth,
                int imageHeight)
        {
            super(context, loadingControl, imageWidth, imageHeight);
        }

        @Override
        protected Bitmap processBitmap(Object data, ProcessingState processingState) {
            ImageData imageData = (ImageData) data;
            return super.processBitmap(imageData.data, processingState);
        }
    }

    public static class CustomImageWorkerAdapter extends
            ImageWorkerAdapter implements Parcelable
    {
        public List<ImageData> all;
        public Set<String> processedValues;

        public List<Integer> filteredIndexes;

        public boolean filtered = false;

        public CustomImageWorkerAdapter()
        {
            loadGallery();
            loadProcessedValues();
            sort();
        }

        public CustomImageWorkerAdapter(
                List<ImageData> all,
                Set<String> processedValues,
                List<Integer> filteredIndexes,
                boolean filtered)
        {
            this.all = all;
            this.processedValues = processedValues;
            this.filteredIndexes = filteredIndexes;
            this.filtered = filtered;
        }

        public void loadGallery()
        {
            long start = System.currentTimeMillis();
            String[] projection =
            {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA
            };
            Cursor cursor = TroveboxApplication.getContext().getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, // Which columns to return
                    null, // Return all rows
                    null,
                    MediaStore.Images.Media.BUCKET_ID);
            if (cursor != null)
            {
                try
                {
                    int count = cursor.getCount();
                    all = new ArrayList<ImageData>(count);
                    while (cursor.moveToNext())
                    {
                        int ind = 0;
                        all.add(new ImageData(cursor.getLong(ind++), cursor
                                .getString(ind)));
                    }
                } finally
                {

                    cursor.close();
                }
            } else
            {
                all = new ArrayList<ImageData>();
            }
            TrackerUtils.trackDataLoadTiming(System.currentTimeMillis() - start, "localGallery",
                    TAG);
        }

        public void loadProcessedValues()
        {
            UploadsProviderAccessor uploads = new UploadsProviderAccessor(
                    TroveboxApplication.getContext());
            List<String> fileNames = uploads
                    .getUploadedOrPendingPhotosFileNames();
            processedValues = new TreeSet<String>(fileNames);
        }

        @Override
        public int getSize()
        {
            return filteredIndexes == null ? all.size() : filteredIndexes
                    .size();
        }

        @Override
        public Object getItem(int num)
        {
            return filteredIndexes == null ? all.get(num) : all
                    .get(filteredIndexes.get(num));
        }

        public void setFiltered(boolean filtered)
        {
            if (filtered)
            {
                long start = System.currentTimeMillis();
                filteredIndexes = new ArrayList<Integer>();

                for (int i = 0, size = all.size(); i < size; i++)
                {
                    ImageData value = all.get(i);
                    if (!isProcessedValue(value))
                    {
                        filteredIndexes.add(i);
                    }
                }
                TrackerUtils.trackDataProcessingTiming(System.currentTimeMillis() - start,
                        "imageFilter", TAG);
            } else
            {
                filteredIndexes = null;
            }
            this.filtered = filtered;
        }

        public boolean isProcessedValue(ImageData value)
        {
            return processedValues.contains(value.data);
        }

        public void addProcessedValues(List<String> values)
        {
            processedValues.addAll(values);
            sort();
            setFiltered(filtered);
        }

        public void clearProcessedValues()
        {
            processedValues.clear();
            sort();
            setFiltered(filtered);
        }

        void sort()
        {
            Collections.sort(all, new Comparator<ImageData>()
            {
                @Override
                public int compare(ImageData lhs, ImageData rhs)
                {
                    int result;
                    boolean leftProcessed = isProcessedValue(lhs);
                    boolean rightProcessed = isProcessedValue(rhs);
                    if (leftProcessed == rightProcessed)
                    {
                        result = 0;
                    } else
                    {
                        result = leftProcessed ? -1 : 1;
                    }
                    if (result == 0)
                    {
                        if (lhs.folder == null) {
                            result = -1;
                        } else if (rhs.folder == null)
                        {
                            result = 1;
                        } else
                        {
                            result = lhs.folder.toLowerCase().compareTo(rhs.folder.toLowerCase());
                        }
                    }
                    return result;
                }
            });
        }

        /*****************************
         * PARCELABLE IMPLEMENTATION *
         *****************************/
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeList(all);
            out.writeInt(processedValues.size());
            for (String value : processedValues)
            {
                out.writeString(value);
            }
            out.writeList(filteredIndexes);
            out.writeByte((byte) (filtered ? 1 : 0));
        }

        public static final Parcelable.Creator<CustomImageWorkerAdapter> CREATOR = new Parcelable.Creator<CustomImageWorkerAdapter>() {
            @Override
            public CustomImageWorkerAdapter createFromParcel(Parcel in) {
                return new CustomImageWorkerAdapter(in);
            }

            @Override
            public CustomImageWorkerAdapter[] newArray(int size) {
                return new CustomImageWorkerAdapter[size];
            }
        };

        @SuppressWarnings("unchecked")
        private CustomImageWorkerAdapter(Parcel in) {
            all = in.readArrayList(getClass().getClassLoader());
            int size = in.readInt();
            processedValues = new TreeSet<String>();
            for (int i = 0; i < size; i++)
            {
                processedValues.add(in.readString());
            }
            filteredIndexes = in.readArrayList(getClass().getClassLoader());
            filtered = in.readByte() == 1;
        }
    }

    public void uploadsCleared()
    {
        if (isDataLoaded())
        {
            customImageWorkerAdapter.clearProcessedValues();
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected boolean isRefreshMenuVisible() {
        return !loadingControl.isLoading();
    }
}
