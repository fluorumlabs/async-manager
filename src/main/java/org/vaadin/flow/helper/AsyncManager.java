package org.vaadin.flow.helper;

import com.vaadin.external.org.slf4j.LoggerFactory;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * Initial configuration of AsyncManager can be done using {@link AsyncManager#setExceptionHandler(Consumer)},
 * {@link AsyncManager#setPollingIntervals(int...)} and {@link AsyncManager#getExecutor()} static methods.
 *
 * @author Artem Godin
 * @see AsyncTask
 * @see AsyncAction
 */
public class AsyncManager implements Serializable {

    /**
     * Register and start a new deferred action. Action are started immediately in a separate thread and do not hold
     * {@code UI} or {@code VaadinSession} locks.
     *
     * @param component Component, where the action needs to be performed, typically your view
     * @param action    Action
     * @return {@link AsyncTask}, associated with this action
     */
    public static AsyncTask register(Component component, AsyncAction action) {
        return register(component, false, action);
    }

    /**
     * Register and start a new deferred action. Action are started immediately in a separate thread and do not hold
     * {@code UI} or {@code VaadinSession} locks. Polling mode can be forced for this action even if push is enabled,
     * which allows to use {@link AsyncTask#sync(Command)} for doing UI operations requiring access to {@code VaadinResponse}
     * (for example, to add a cookie)
     *
     * @param component    Component, where the action needs to be performed, typically your view
     * @param forcePolling If <tt>true</tt>, polling will be used even if push is enabled
     * @param action       Action
     * @return {@link AsyncTask}, associated with this action
     */
    public static AsyncTask register(Component component, boolean forcePolling, AsyncAction action) {
        Objects.requireNonNull(component);

        AsyncTask asyncTask = new AsyncTask();
        Optional<UI> uiOptional = component.getUI();
        if (uiOptional.isPresent()) {
            asyncTask.register(uiOptional.get(), component, forcePolling, action);
        } else {
            component.addAttachListener(attachEvent -> {
                attachEvent.unregisterListener();
                asyncTask.register(attachEvent.getUI(), component, forcePolling, action);
            });
        }
        return asyncTask;
    }

    /**
     * Get a {@link ThreadPoolExecutor} used for asynchronous task execution. This can be used to
     * adjust thread pool size.
     *
     * @return static instance of {@link ThreadPoolExecutor}
     */
    public static ThreadPoolExecutor getExecutor() {
        return executor;
    }

    /**
     * Set custom exception handler for exceptions thrown in async tasks if you need custom logging or
     * reporting
     *
     * @param handler Exception handler to set
     */
    public static void setExceptionHandler(Consumer<Exception> handler) {
        AsyncManager.exceptionHandler = handler;
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
    public static void setPollingIntervals(int... milliseconds) {
        if (milliseconds.length == 0) {
            AsyncManager.pollingIntervals = DEFAULT_POLLING_INTERVALS;
        }
        AsyncManager.pollingIntervals = milliseconds;
    }

    private AsyncManager() { // Not directly instantiatable
    }

    /**
     * Default pool size (25 threads)
     */
    private static final int DEFAULT_POOL_SIZE = 25;

    /**
     * Default polling intervals (200 ms)
     */
    private static final int[] DEFAULT_POLLING_INTERVALS = {200};

    /**
     * Instance of {@link ThreadPoolExecutor} used for asynchronous tasks
     */
    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE,
            0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

    /**
     * List of all registered {@link AsyncTask} per component instance
     */
    private static Map<UI, Set<AsyncTask>> asyncTasks = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Exception handler
     */
    private static Consumer<Exception> exceptionHandler = AsyncManager::logException;

    /**
     * Polling intervals
     */
    private static int[] pollingIntervals = DEFAULT_POLLING_INTERVALS;

    /**
     * Default exception handler that simply logs the exception
     *
     * @param e Exception to handle
     */
    private static void logException(Throwable e) {
        LoggerFactory.getLogger(AsyncManager.class.getName()).warn(e.getMessage(), e);
    }

    /**
     * Get list of active asynchronous tasks for specified component
     *
     * @param ui Owning UI
     * @return Set of {@link AsyncTask}
     */
    private static Set<AsyncTask> getAsyncTasks(UI ui) {
        return asyncTasks.computeIfAbsent(ui, parentComponent -> Collections.synchronizedSet(new HashSet<>()));
    }

    /**
     * Add {@link AsyncTask} to the {@link #asyncTasks} for current component
     *
     * @param ui        Owning UI
     * @param task      Task
     */
    static void addAsyncTask(UI ui, AsyncTask task) {
        AsyncManager.getAsyncTasks(ui).add(task);
    }

    /**
     * Remove {@link AsyncTask} from the {@link #asyncTasks} for current component
     *
     * @param ui        Owning UI
     * @param task      Task
     */
    static void removeAsyncTask(UI ui, AsyncTask task) {
        AsyncManager.getAsyncTasks(ui).remove(task);
    }

    /**
     * Adjust polling interval for specified component.
     *
     * @param ui        UI, associated with current task
     */
    static void adjustPollingInterval(UI ui) {
        int newInterval = AsyncManager.getAsyncTasks(ui).stream()
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

    /**
     * Exception handler delegating handling to {@link AsyncManager#exceptionHandler}
     *
     * @param e Exception to handle
     */
    static void handleException(Exception e) {
        AsyncManager.exceptionHandler.accept(e);
    }

    /**
     * Get polling intervals
     *
     * @return polling intervals in milliseconds
     */
    static int[] getPollingIntervals() {
        return pollingIntervals;
    }
}