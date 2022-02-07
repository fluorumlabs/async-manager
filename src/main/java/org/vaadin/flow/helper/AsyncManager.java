package org.vaadin.flow.helper;

import com.vaadin.external.org.slf4j.LoggerFactory;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper class for executing asynchronous tasks in your views using push or dynamic polling.
 * <p>
 * Typically actions required deferred execution are computationally heavy, but the computation itself
 * do not require {@code UI} lock. This helper allows to simplify deferred task creation:
 * <p>
 * <pre><code>
 *     AsyncManager.register(this, task -> {
 *         doSomeHeavylifting();
 *         task.push({
 *             updateView();
 *         });
 *     });
 * </code></pre>
 * <p>
 * This will start a new thread and then, when the results of computation will be available, will push it
 * to the client. If push is not enabled in your application, it will use polling instead. {@code AsyncManager} takes care
 * of interrupting tasks when the view is detached from UI or if the UI leaves current view.
 * <p>
 * Initial configuration of AsyncManager can be done using {@link AsyncManager#setExceptionHandler(ExceptionHandler)},
 * {@link AsyncManager#setPollingIntervals(int...)} and {@link AsyncManager#setExecutorService(ExecutorService)} static methods.
 *
 * @author Artem Godin
 * @see AsyncTask
 * @see Action
 */
public final class AsyncManager {
    //--- Defaults

    /**
     * Default pool size (25 threads)
     */
    private static final int DEFAULT_POOL_SIZE = 25;
    /**
     * Default polling intervals (200 ms)
     */
    private static final int[] DEFAULT_POLLING_INTERVALS = {200};

    //--- The one and only instance of AsyncManager

    /**
     * Instance of AsyncManager
     */
    private static final AsyncManager instance = new AsyncManager();

    //-- Private fields

    /**
     * Exception handler
     */
    private ExceptionHandler exceptionHandler = AsyncManager::logException;
    /**
     * Task state handler
     */
    private TaskStateHandler taskStateHandler;

    /**
     * Instance of {@link ExecutorService} used for asynchronous tasks
     */
    private ExecutorService executorService = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    /**
     * Polling intervals
     */
    private int[] pollingIntervals = DEFAULT_POLLING_INTERVALS;

    private AsyncManager() { // Not directly instantiatable
    }

    //--- Static methods

    /**
     * Get instance of AsyncManager
     *
     * @return Instance of AsyncManager
     */
    public static AsyncManager getInstance() {
        return instance;
    }

    /**
     * Register and start a new deferred action. Action are started immediately in a separate thread and do not hold
     * {@code UI} or {@code VaadinSession} locks.
     * <p>
     * Shorthand for {@code AsyncManager.getInstance().registerAsync(component, action)}
     *
     * @param component Component, where the action needs to be performed, typically your view
     * @param action    Action
     * @return {@link Task}, associated with this action
     */
    public static Task register(Component component, Action action) {
        return getInstance().registerAsync(component, action);
    }

    /**
     * Register and runs eager action. Action are started immediately in the same thread.
     * <p>
     * Shorthand for {@code AsyncManager.getInstance().registerSync(component, action)}
     *
     * @param component Component, where the action needs to be performed, typically your view
     * @param action    Action
     * @return {@link Task}, associated with this action
     */
    public static Task run(Component component, Action action) {
        return getInstance().registerSync(component, action);
    }

    /**
     * Default exception handler that simply logs the exception
     *
     * @param task Task where exception happened
     * @param e    Exception to handle
     */
    private static void logException(Task task, Throwable e) {
        LoggerFactory.getLogger(AsyncManager.class.getName()).warn(e.getMessage(), e);
    }

    //--- Getters and setters

    /**
     * Set custom exception handler for exceptions thrown in async tasks if you need custom logging or
     * reporting
     *
     * @param handler Exception handler to set
     */
    public void setExceptionHandler(ExceptionHandler handler) {
        exceptionHandler = handler;
    }

    /**
     * Set custom task state handler to monitor
     * @param handler State handler to set
     */
    public void setTaskStateHandler(TaskStateHandler handler) {
        taskStateHandler = handler;
    }


    /**
     * Get a {@link ExecutorService} used for asynchronous task execution.
     *
     * @return static instance of {@link ExecutorService}
     */
    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Set {@link ExecutorService} to be used for asynchronous task execution.
     */
    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    /**
     * Get polling intervals
     *
     * @return polling intervals in milliseconds
     */
    int[] getPollingIntervals() {
        return pollingIntervals;
    }

    /**
     * Set polling intervals for polling mode. When multiple values are specified, the
     * resulting polling intervals will automatically adjust. For example,
     * {@code setPollingIntervals(200, 200, 200, 200, 200, 500, 500, 1000)} will make the first
     * 5 polls with 200 ms interval, the next 2 - with 500 ms and after than 1 sec intervals will be used.
     * <p>
     * This can be used to reduce amount of client requests when really computationally-heavy tasks
     * are executed.
     *
     * @param milliseconds Polling intervals in milliseconds
     */
    public void setPollingIntervals(int... milliseconds) {
        if (milliseconds.length == 0) {
            pollingIntervals = DEFAULT_POLLING_INTERVALS;
        }
        pollingIntervals = milliseconds;
    }

    /**
     * Register and start a new deferred action. Action are started immediately in a separate thread and do not hold
     * {@code UI} or {@code VaadinSession} locks.
     *
     * @param component Component, where the action needs to be performed, typically your view
     * @param action    Action
     * @return {@link Task}, associated with this action
     */
    public Task registerSync(Component component, Action action) {
        Objects.requireNonNull(component);

        Task task = new SyncTask(this);
        registerTask(component, task, action);
        return task;
    }

    /**
     * Register and start a new deferred action. Action are started immediately in a separate thread and do not hold
     * {@code UI} or {@code VaadinSession} locks.
     *
     * @param component Component, where the action needs to be performed, typically your view
     * @param action    Action
     * @return {@link Task}, associated with this action
     */
    public Task registerAsync(Component component, Action action) {
        Objects.requireNonNull(component);

        Task task = new AsyncTask(this);
        registerTask(component, task, action);
        return task;
    }

    //--- Implementation

    void registerTask(Component component, Task task, Action action) {
        UI ui = component.getUI().orElse(null);
        if (ui != null) {
            task.register(ui, component, action);
        } else {
            component.addAttachListener(attachEvent -> {
                attachEvent.unregisterListener();
                task.register(attachEvent.getUI(), component, action);
            });
        }
    }

    /**
     * Get list of active asynchronous tasks for specified component
     *
     * @param ui Owning UI
     * @return Set of {@link AsyncTask}
     */
    @SuppressWarnings("unchecked")
    private Set<AsyncTask> getAsyncTasks(UI ui) {
        synchronized (ui) {
            Set<AsyncTask> asyncTasks = (Set<AsyncTask>) ComponentUtil.getData(ui, getClass().getName());
            if (asyncTasks == null) {
                asyncTasks = Collections.synchronizedSet(new HashSet<>());
                ComponentUtil.setData(ui, getClass().getName(), asyncTasks);
            }

            return asyncTasks;
        }
    }

    /**
     * Add {@link AsyncTask} for current component
     *
     * @param ui   Owning UI
     * @param task Task
     */
    void addAsyncTask(UI ui, AsyncTask task) {
        getAsyncTasks(ui).add(task);
    }

    /**
     * Remove {@link AsyncTask} for current component
     *
     * @param ui   Owning UI
     * @param task Task
     */
    void removeAsyncTask(UI ui, AsyncTask task) {
        getAsyncTasks(ui).remove(task);
    }

    /**
     * Adjust polling interval for specified component.
     *
     * @param ui UI, associated with current task
     */
    void adjustPollingInterval(UI ui) {
        Set<AsyncTask> tasks = getAsyncTasks(ui);

        synchronized (tasks) {
            int newInterval = tasks.stream()
                    .map(AsyncTask::getPollingInterval)
                    .sorted()
                    .findFirst().orElse(Integer.MAX_VALUE);
            if (newInterval < Integer.MAX_VALUE) {
                if (newInterval != ui.getPollInterval()) {
                    ui.setPollInterval(newInterval);
                }
            } else {
                if (-1 != ui.getPollInterval()) {
                    ui.setPollInterval(-1);
                }
            }
        }
    }

    /**
     * Exception handler delegating handling to {@link AsyncManager#exceptionHandler}
     *
     * @param e Exception to handle
     */
    void handleException(Task task, Exception e) {
        exceptionHandler.handle(task, e);
    }

    void handleTaskStateChanged(Task task, TaskStateHandler.State state) {
        if (taskStateHandler != null) {
            taskStateHandler.taskChanged(task, state);
        }
    }

    /**
     * Functional interface for exception handling
     */
    @FunctionalInterface
    public interface ExceptionHandler {
        /**
         * Handle exception happened during {@code task} execution.
         *
         * @param task AsyncTask where exception happened
         * @param e    Exception
         */
        void handle(Task task, Exception e);
    }

    /**
     * Functional interface for task state handling
     */
    @FunctionalInterface
    public interface TaskStateHandler {
        enum State {
            /**
             * Task started
             */
            RUNNING,
            /**
             * Task done
             */
            DONE,
            /**
             * Task was canceled
             */
            CANCELED
        }

        /**
         * Handle state change during task execution.
         * @param task Task where state change happened
         * @param state State of the given task
         */
        void taskChanged(Task task, State state);
    }
}