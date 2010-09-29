/**
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.gtasks.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.json.JSONException;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import com.flurry.android.FlurryAgent;
import com.timsu.astrid.R;
import com.todoroo.andlib.data.AbstractModel;
import com.todoroo.andlib.data.Property;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.DialogUtilities;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.StoreObject;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.gtasks.GtasksList;
import com.todoroo.astrid.gtasks.GtasksListService;
import com.todoroo.astrid.gtasks.GtasksMetadata;
import com.todoroo.astrid.gtasks.GtasksMetadataService;
import com.todoroo.astrid.gtasks.GtasksPreferenceService;
import com.todoroo.astrid.gtasks.GtasksPreferences;
import com.todoroo.astrid.gtasks.GtasksTaskListUpdater;
import com.todoroo.astrid.producteev.ProducteevBackgroundService;
import com.todoroo.astrid.producteev.ProducteevLoginActivity;
import com.todoroo.astrid.producteev.ProducteevUtilities;
import com.todoroo.astrid.producteev.api.ApiServiceException;
import com.todoroo.astrid.service.AstridDependencyInjector;
import com.todoroo.astrid.sync.SyncContainer;
import com.todoroo.astrid.sync.SyncProvider;
import com.todoroo.astrid.utility.Constants;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.gtasks.GoogleTaskService;
import com.todoroo.gtasks.GoogleTaskService.ConvenientTaskCreator;
import com.todoroo.gtasks.GoogleTaskTask;
import com.todoroo.gtasks.GoogleTaskView;
import com.todoroo.gtasks.GoogleTasksException;
import com.todoroo.gtasks.actions.Action;
import com.todoroo.gtasks.actions.Actions;
import com.todoroo.gtasks.actions.GetTasksAction;
import com.todoroo.gtasks.actions.ListAction;
import com.todoroo.gtasks.actions.ListActions;
import com.todoroo.gtasks.actions.ListActions.TaskBuilder;
import com.todoroo.gtasks.actions.ListActions.TaskModifier;

@SuppressWarnings("nls")
public class GtasksSyncProvider extends SyncProvider<GtasksTaskContainer> {

    @Autowired private GtasksListService gtasksListService;
    @Autowired private GtasksMetadataService gtasksMetadataService;
    @Autowired private GtasksPreferenceService gtasksPreferenceService;
    @Autowired private GtasksTaskListUpdater gtasksTaskListUpdater;

    /** google task service fields */
    private GoogleTaskService taskService = null;
    private static final Actions a = new Actions();
    private static final ListActions l = new ListActions();

    /** batched actions to execute */
    private final ArrayList<Action> actions = new ArrayList<Action>();
    private final HashMap<String, ArrayList<ListAction>> listActions =
        new HashMap<String, ArrayList<ListAction>>();


    static {
        AstridDependencyInjector.initialize();
    }

    @Autowired
    protected ExceptionService exceptionService;

    public GtasksSyncProvider() {
        super();
        DependencyInjectionService.getInstance().inject(this);
    }

    // ----------------------------------------------------------------------
    // ------------------------------------------------------ utility methods
    // ----------------------------------------------------------------------

    /**
     * Sign out of service, deleting all synchronization metadata
     */
    public void signOut() {
        gtasksPreferenceService.clearLastSyncDate();
        gtasksPreferenceService.setToken(null);

        gtasksMetadataService.clearMetadata();
    }

    /**
     * Deal with a synchronization exception. If requested, will show an error
     * to the user (unless synchronization is happening in background)
     *
     * @param context
     * @param tag
     *            error tag
     * @param e
     *            exception
     * @param showError
     *            whether to display a dialog
     */
    @Override
    protected void handleException(String tag, Exception e, boolean displayError) {
        final Context context = ContextManager.getContext();
        gtasksPreferenceService.setLastError(e.toString());

        String message = null;

        // occurs when application was closed
        if(e instanceof IllegalStateException) {
            exceptionService.reportError(tag + "-caught", e); //$NON-NLS-1$

            // occurs when network error
        } else if(!(e instanceof ApiServiceException) && e instanceof IOException) {
            message = context.getString(R.string.producteev_ioerror);
        } else {
            message = context.getString(R.string.DLG_error, e.toString());
            exceptionService.reportError(tag + "-unhandled", e); //$NON-NLS-1$
        }

        if(displayError && context instanceof Activity && message != null) {
            DialogUtilities.okDialog((Activity)context,
                    message, null);
        }
    }

    // ----------------------------------------------------------------------
    // ------------------------------------------------------ initiating sync
    // ----------------------------------------------------------------------

    /**
     * initiate sync in background
     */
    @Override
    protected void initiateBackground(Service service) {
        try {
            String authToken = gtasksPreferenceService.getToken();

            String email = "tasktest@todoroo.com"; // TODO
            String password = "tasktest0000";

            taskService = new GoogleTaskService(email, password);

            // check if we have a token & it works
            if(authToken != null) {

                taskService.getTaskView();
                performSync();
            } else {
                if (email == null && password == null) {
                    // we can't do anything, user is not logged in
                } else {
                    //authToken = null; // TODO set up auth token
                    performSync();
                }
            }
        } catch (IllegalStateException e) {
            // occurs when application was closed
        } catch (Exception e) {
            handleException("gtasks-authenticate", e, true);
        } finally {
            gtasksPreferenceService.stopOngoing();
        }
    }

    /**
     * If user isn't already signed in, show sign in dialog. Else perform sync.
     */
    @Override
    protected void initiateManual(Activity activity) {
        String authToken = gtasksPreferenceService.getToken();
        ProducteevUtilities.INSTANCE.stopOngoing();

        // check if we have a token & it works
        if(authToken == null) {
            // display login-activity
            Intent intent = new Intent(activity, ProducteevLoginActivity.class);
            activity.startActivityForResult(intent, 0);
        } else {
            activity.startService(new Intent(ProducteevBackgroundService.SYNC_ACTION, null,
                    activity, ProducteevBackgroundService.class));
        }
    }

    // ----------------------------------------------------------------------
    // ----------------------------------------------------- synchronization!
    // ----------------------------------------------------------------------

    protected void performSync() {
        FlurryAgent.onEvent("gtasks-started");
        gtasksPreferenceService.recordSyncStart();

        try {
            GoogleTaskView taskView = taskService.getTaskView();
            Preferences.setString(GtasksPreferenceService.PREF_DEFAULT_LIST,
                    taskView.getActiveTaskList().getInfo().getId());

            gtasksListService.updateLists(taskView.getAllLists());

            gtasksTaskListUpdater.createParentSiblingMaps();

            // batched read tasks for each list
            ArrayList<GtasksTaskContainer> remoteTasks = new ArrayList<GtasksTaskContainer>();
            ArrayList<GetTasksAction> getTasksActions = new ArrayList<GetTasksAction>();
            for(StoreObject dashboard : gtasksListService.getLists()) {
                String listId = dashboard.getValue(GtasksList.REMOTE_ID);
                getTasksActions.add(a.getTasks(listId, true));
            }
            taskService.executeActions(getTasksActions.toArray(new GetTasksAction[getTasksActions.size()]));
            for(GetTasksAction action : getTasksActions) {
                List<GoogleTaskTask> remoteTasksInList = action.getGoogleTasks();
                for(GoogleTaskTask remoteTask : remoteTasksInList) {
                    GtasksTaskContainer remote = parseRemoteTask(remoteTask);
                    // update reminder flags for incoming remote tasks to prevent annoying
                    if(remote.task.hasDueDate() && remote.task.getValue(Task.DUE_DATE) < DateUtilities.now())
                        remote.task.setFlag(Task.REMINDER_FLAGS, Task.NOTIFY_AFTER_DEADLINE, false);
                    gtasksMetadataService.findLocalMatch(remote);
                    remoteTasks.add(remote);
                }
            }

            SyncData<GtasksTaskContainer> syncData = populateSyncData(remoteTasks);
            try {
                synchronizeTasks(syncData);
            } finally {
                syncData.localCreated.close();
                syncData.localUpdated.close();
            }

            gtasksPreferenceService.recordSuccessfulSync();
            FlurryAgent.onEvent("gtasks-sync-finished"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
        	// occurs when application was closed
        } catch (Exception e) {
            handleException("gtasks-sync", e, true); //$NON-NLS-1$
        }
    }

    // ----------------------------------------------------------------------
    // ------------------------------------------------------------ sync data
    // ----------------------------------------------------------------------

    // all synchronized properties
    private static final Property<?>[] PROPERTIES = new Property<?>[] {
            Task.ID,
            Task.TITLE,
            Task.IMPORTANCE,
            Task.DUE_DATE,
            Task.CREATION_DATE,
            Task.COMPLETION_DATE,
            Task.DELETION_DATE,
            Task.REMINDER_FLAGS,
            Task.NOTES,
    };

    /**
     * Populate SyncData data structure
     * @throws JSONException
     */
    private SyncData<GtasksTaskContainer> populateSyncData(ArrayList<GtasksTaskContainer> remoteTasks) throws JSONException {
        // fetch locally created tasks
        TodorooCursor<Task> localCreated = gtasksMetadataService.getLocallyCreated(PROPERTIES);

        // fetch locally updated tasks
        TodorooCursor<Task> localUpdated = gtasksMetadataService.getLocallyUpdated(PROPERTIES);

        return new SyncData<GtasksTaskContainer>(remoteTasks, localCreated, localUpdated);
    }

    // ----------------------------------------------------------------------
    // ------------------------------------------------- create / push / pull
    // ----------------------------------------------------------------------

    @Override
    protected GtasksTaskContainer create(GtasksTaskContainer local) throws IOException {
        String list = Preferences.getStringValue(GtasksPreferenceService.PREF_DEFAULT_LIST);
        if(local.gtaskMetadata.containsNonNullValue(GtasksMetadata.LIST_ID))
            list = local.gtaskMetadata.getValue(GtasksMetadata.LIST_ID);

        ConvenientTaskCreator createdTask;
        try {
            createdTask = taskService.createTask(list, local.task.getValue(Task.TITLE));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        updateTaskHelper(local, null, createdTask);
        String remoteId;
        try {
            remoteId = createdTask.go();
        } catch (JSONException e) {
            throw new GoogleTasksException(e);
        }
        local.gtaskMetadata.setValue(GtasksMetadata.LIST_ID, remoteId);

        return local;
    }

    private void updateTaskHelper(GtasksTaskContainer local,
            GtasksTaskContainer remote, TaskBuilder<?> builder) throws IOException {

        String idTask = local.gtaskMetadata.getValue(GtasksMetadata.ID);
        String idList = local.gtaskMetadata.getValue(GtasksMetadata.LIST_ID);

        // fetch remote task for comparison
        if(remote == null && idTask != null)
            remote = pull(local);

        try {

            // moving between lists
            if(remote != null && !idList.equals(remote.gtaskMetadata.getValue(GtasksMetadata.LIST_ID))) {
                a.moveTask(idTask, idList, remote.gtaskMetadata.getValue(GtasksMetadata.LIST_ID), null);
            }

            // other properties
            if(shouldTransmit(local, Task.TITLE, remote))
                ((TaskModifier)builder).name(local.task.getValue(Task.TITLE));
            if(shouldTransmit(local, Task.DUE_DATE, remote))
                builder.taskDate(local.task.getValue(Task.DUE_DATE));
            if(shouldTransmit(local, Task.COMPLETION_DATE, remote))
                builder.completed(local.task.isCompleted());
            if(shouldTransmit(local, Task.DELETION_DATE, remote))
                builder.deleted(local.task.isDeleted());
            if(shouldTransmit(local, Task.NOTES, remote))
                builder.notes(local.task.getValue(Task.NOTES));

        } catch (JSONException e) {
            throw new GoogleTasksException(e);
        }

        // TODO indentation
    }

    /** Create a task container for the given RtmTaskSeries
     * @throws JSONException */
    private GtasksTaskContainer parseRemoteTask(GoogleTaskTask remoteTask) {
        Task task = new Task();
        ArrayList<Metadata> metadata = new ArrayList<Metadata>();

        task.setValue(Task.TITLE, remoteTask.getName());
        task.setValue(Task.CREATION_DATE, DateUtilities.now());
        task.setValue(Task.COMPLETION_DATE, remoteTask.getCompleted_date());
        task.setValue(Task.DELETION_DATE, remoteTask.isDeleted() ? DateUtilities.now() : 0);

        long dueDate = remoteTask.getTask_date();
        task.setValue(Task.DUE_DATE, task.createDueDate(Task.URGENCY_SPECIFIC_DAY_TIME, dueDate));
        task.setValue(Task.NOTES, remoteTask.getNotes());

        Metadata gtasksMetadata = GtasksMetadata.createEmptyMetadata(AbstractModel.NO_ID);
        gtasksMetadata.setValue(GtasksMetadata.LIST_ID, remoteTask.getList_id());
        // TODO gtasksMetadata.setValue(GtasksMetadata.INDENT, remoteTask.???);

        GtasksTaskContainer container = new GtasksTaskContainer(task, metadata,
                gtasksMetadata);

        return container;
    }

    @Override
    protected GtasksTaskContainer pull(GtasksTaskContainer task) throws IOException {
        if(!task.gtaskMetadata.containsNonNullValue(GtasksMetadata.ID) ||
                !task.gtaskMetadata.containsNonNullValue(GtasksMetadata.LIST_ID))
            throw new ApiServiceException("Tried to read an invalid task"); //$NON-NLS-1$

        String idToMatch = task.gtaskMetadata.getValue(GtasksMetadata.ID);
        List<GoogleTaskTask> tasks;
        try {
            tasks = taskService.getTasks(task.gtaskMetadata.getValue(GtasksMetadata.LIST_ID));
        } catch (JSONException e) {
            throw new GoogleTasksException(e);
        }
        for(GoogleTaskTask remoteTask : tasks) {
            if(remoteTask.getId().equals(idToMatch)) {
                return parseRemoteTask(remoteTask);
            }
        }

        throw new GoogleTasksException("Could not find remote task to pull.");
    }

    /**
     * Send changes for the given Task across the wire. If a remoteTask is
     * supplied, we attempt to intelligently only transmit the values that
     * have changed.
     */
    @Override
    protected void push(GtasksTaskContainer local, GtasksTaskContainer remote) throws IOException {
        try {
            TaskModifier modifyTask = l.modifyTask(remote.gtaskMetadata.getValue(GtasksMetadata.ID));
            updateTaskHelper(local, remote, modifyTask);
            ListAction action = modifyTask.done();
            batch(local.gtaskMetadata.getValue(GtasksMetadata.LIST_ID), action);
        } catch (JSONException e) {
            throw new GoogleTasksException(e);
        }
    }

    /** add action to batch */
    private void batch(String list, ListAction action) {
        if(!listActions.containsKey(list))
            listActions.put(list, new ArrayList<ListAction>());
        listActions.get(list).add(action);
    }

    // ----------------------------------------------------------------------
    // --------------------------------------------------------- read / write
    // ----------------------------------------------------------------------

    @Override
    protected GtasksTaskContainer read(TodorooCursor<Task> cursor) throws IOException {
        return gtasksMetadataService.readTaskAndMetadata(cursor);
    }

    @Override
    protected void write(GtasksTaskContainer task) throws IOException {
        gtasksMetadataService.saveTaskAndMetadata(task);
    }

    // ----------------------------------------------------------------------
    // --------------------------------------------------------- misc helpers
    // ----------------------------------------------------------------------

    @Override
    protected int matchTask(ArrayList<GtasksTaskContainer> tasks, GtasksTaskContainer target) {
        int length = tasks.size();
        for(int i = 0; i < length; i++) {
            GtasksTaskContainer task = tasks.get(i);
            if(AndroidUtilities.equals(task.gtaskMetadata, target.gtaskMetadata))
                return i;
        }
        return -1;
    }

    /**
     * Determine whether this task's property should be transmitted
     * @param task task to consider
     * @param property property to consider
     * @param remoteTask remote task proxy
     * @return
     */
    private boolean shouldTransmit(SyncContainer task, Property<?> property, SyncContainer remoteTask) {
        if(!task.task.containsValue(property))
            return false;

        if(remoteTask == null)
            return true;
        if(!remoteTask.task.containsValue(property))
            return true;

        // special cases - match if they're zero or nonzero
        if(property == Task.COMPLETION_DATE ||
                property == Task.DELETION_DATE)
            return !AndroidUtilities.equals((Long)task.task.getValue(property) == 0,
                    (Long)remoteTask.task.getValue(property) == 0);

        return !AndroidUtilities.equals(task.task.getValue(property),
                remoteTask.task.getValue(property));
    }

    @Override
    protected int updateNotification(Context context, Notification notification) {
        String notificationTitle = context.getString(R.string.gtasks_notification_title);
        Intent intent = new Intent(context, GtasksPreferences.class);
        PendingIntent notificationIntent = PendingIntent.getActivity(context, 0,
                intent, 0);
        notification.setLatestEventInfo(context,
                notificationTitle, context.getString(R.string.SyP_progress),
                notificationIntent);
        return Constants.NOTIFICATION_SYNC;
    }

    @Override
    protected void transferIdentifiers(GtasksTaskContainer source,
            GtasksTaskContainer destination) {
        destination.gtaskMetadata = source.gtaskMetadata;
    }
}
