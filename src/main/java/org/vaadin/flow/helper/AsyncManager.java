package org.vaadin.flow.helper;

import com.vaadin.external.org.slf4j.LoggerFactory;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Helper class for executing asynchronous tasks in your views using push or dynamic polling.
 * <p>
 * Typically actions required deferred execution are computationally heavy, but the computation itself
 * do not require {@code UI} lock. This helper allows to simplify deferred task creation:
 * <p>
 * <pre><code>
 *     AsyncManager.register(this, asyncTask -> {
 *         doSomeHeavylifting();
 *         asyncTask.push({
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
 * @see AsyncAction
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

    private static final String ASYNC_TASKS_KEY = "org.vaadin.flow.helper.AsyncManager";

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
     * @return {@link AsyncTask}, associated with this action
     */
    public static AsyncTask register(Component component, AsyncAction action) {
        return getInstance().registerAsync(component, action);
    }

    /**
     * Default exception handler that simply logs the exception
     *
     * @param task AsyncTask where exception happened
     * @param e    Exception to handle
     */
    private static void logException(AsyncTask task, Throwable e) {
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
     * @return {@link AsyncTask}, associated with this action
     */
    public AsyncTask registerAsync(Component component, AsyncAction action) {
        Objects.requireNonNull(component);

        AsyncTask asyncTask = new AsyncTask(this);
        UI ui = component.getUI().orElse(null);
        if (ui != null) {
            asyncTask.register(ui, component, action);
        } else {
            component.addAttachListener(attachEvent -> {
                attachEvent.unregisterListener();
                asyncTask.register(attachEvent.getUI(), component, action);
            });
        }
        return asyncTask;
    }

    //--- Implementation

    /**
     * Get list of active asynchronous tasks for specified component
     *
     * @param ui Owning UI
     * @return Set of {@link AsyncTask}
     */
    @SuppressWarnings("unchecked")
    private Set<AsyncTask> getAsyncTasks(UI ui) {
        Set<AsyncTask> asyncTasks = (Set<AsyncTask>) ComponentUtil.getData(ui, ASYNC_TASKS_KEY);
        if ( asyncTasks == null ) {
            asyncTasks = Collections.synchronizedSet(new HashSet<>());
            ComponentUtil.setData(ui, ASYNC_TASKS_KEY, asyncTasks);
        }
        return asyncTasks;
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
    void handleException(AsyncTask task, Exception e) {
        exceptionHandler.handle(task, e);
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
        void handle(AsyncTask task, Exception e);
    }
}