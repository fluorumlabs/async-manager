package org.vaadin.flow.helper;

import com.vaadin.flow.component.*;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.shared.communication.PushMode;

import java.util.concurrent.FutureTask;

/**
 * Asynchronous task created by {@link AsyncManager#register(Component, AsyncAction)}
 *
 * @author Artem Godin
 * @see AsyncManager
 */
public class AsyncTask {
    //--- Defaults

    /**
     * Value for {@link #missedPolls} for push enabled tasks
     */
    private static final int PUSH_ACTIVE = -1;

    //--- Fields

    /**
     * Intance of AsyncManager handling this task
     */
    private final AsyncManager asyncManager;
    /**
     * {@link FutureTask} representing action
     */
    private FutureTask<AsyncTask> task;
    /**
     * Owning UI for current task
     */
    private UI parentUI;
    /**
     * Registration for PollEvent listener
     */
    private Registration pollingListenerRegistration;
    /**
     * Registration for DetachEvent listener
     */
    private Registration componentDetachListenerRegistration;
    /**
     * Registration for UI DetachEvent listener
     */
    private Registration uiDetachListenerRegistration;
    /**
     * Registration for BeforeLeave event listener
     */
    private Registration beforeLeaveListenerRegistration;
    /**
     * Number of poll events happened while action is executing, or {@link #PUSH_ACTIVE} if
     * push is used for current task
     */
    private volatile int missedPolls = 0;

    /**
     * Create a new task
     */
    AsyncTask(AsyncManager asyncManager) {
        this.asyncManager = asyncManager;
    }

    //--- Public methods

    /**
     * Perform command in UI context. It uses {@link UI#accessSynchronously(Command)} internally.
     *
     * @param command Command to run
     */
    public void push(Command command) {
        if (parentUI == null) return;
        if (missedPolls == PUSH_ACTIVE && parentUI.getPushConfiguration().getPushMode() == PushMode.MANUAL) {
            parentUI.accessSynchronously(() -> {
                command.execute();
                parentUI.push();
            });
        } else {
            // Automatic -- changes will be pushed automatically
            // Disabled -- we're using polling and this is called
            //             within UIDLRequestHandler
            parentUI.accessSynchronously(command);
        }
    }

    /**
     * Cancel and unregister the task
     */
    public void cancel() {
        if (!task.isCancelled() && !task.isDone()) {
            task.cancel(true);
        }
        remove();
    }

    //--- Implementation

    /**
     * Register action
     *
     * @param ui     UI owning current view
     * @param action Action
     */
    void register(UI ui, Component component, AsyncAction action) {
        this.parentUI = ui;
        if (ui.getPushConfiguration().getPushMode().isEnabled()) {
            registerPush(component, action);
        } else {
            registerPoll(component, action);
        }
    }

    /**
     * Register action for push mode
     *
     * @param action Action
     */
    private void registerPush(Component component, AsyncAction action) {
        add();
        missedPolls = PUSH_ACTIVE;

        task = createFutureTask(action);

        componentDetachListenerRegistration = component.addDetachListener(this::onDetachEvent);
        uiDetachListenerRegistration = parentUI.addDetachListener(this::onDetachEvent);
        beforeLeaveListenerRegistration = parentUI.addBeforeLeaveListener(this::onBeforeLeaveEvent);

        execute();
    }

    /**
     * Register action for polling
     *
     * @param action Action
     */
    private void registerPoll(Component component, AsyncAction action) {
        add();

        task = createFutureTask(action);

        pollingListenerRegistration = parentUI.addPollListener(this::onPollEvent);

        uiDetachListenerRegistration = parentUI.addDetachListener(this::onDetachEvent);
        componentDetachListenerRegistration = component.addDetachListener(this::onDetachEvent);
        beforeLeaveListenerRegistration = parentUI.addBeforeLeaveListener(this::onBeforeLeaveEvent);

        asyncManager.adjustPollingInterval(parentUI);
        execute();
    }

    /**
     * Wrap action with {@link FutureTask}
     *
     * @param action Action
     * @return Action wrapped with exception handling
     */
    private FutureTask<AsyncTask> createFutureTask(AsyncAction action) {
        return new FutureTask<>(() -> {
            try {
                action.run(this);
            } catch (UIDetachedException ignore) {
                // Do not report
            } catch (InterruptedException e) {
                // Interrupt current thread
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Dump
                asyncManager.handleException(e);
            } finally {
                remove();
            }
        }, this);
    }

    /**
     * Execute task in {@link AsyncManager#getExecutorService()} executor
     */
    private void execute() {
        asyncManager.getExecutorService().execute(task);
    }

    /**
     * Add current task to {@link AsyncManager#asyncTasks}
     */
    private void add() {
        asyncManager.addAsyncTask(parentUI, this);
    }

    /**
     * Remove current task from {@link AsyncManager#asyncTasks} and unregister all listeners
     */
    private synchronized void remove() {
        if (parentUI != null) {
            asyncManager.removeAsyncTask(parentUI, this);
            // Polling interval needs to be adjusted if task is finished
            try {
                parentUI.accessSynchronously(() -> {
                    asyncManager.adjustPollingInterval(parentUI);

                    if (componentDetachListenerRegistration != null) componentDetachListenerRegistration.remove();
                    if (uiDetachListenerRegistration != null) uiDetachListenerRegistration.remove();
                    if (pollingListenerRegistration != null) pollingListenerRegistration.remove();
                    if (beforeLeaveListenerRegistration != null) beforeLeaveListenerRegistration.remove();
                });
            } catch (UIDetachedException ignore) {
                // ignore detached ui -- there will be no polling events for them anyway
            }

            parentUI = null;
        }
    }

    /**
     * Get current polling interval based on {@link #missedPolls} and {@link AsyncManager#pollingIntervals}
     *
     * @return Polling interval in milliseconds
     */
    int getPollingInterval() {
        int missed = missedPolls;
        if (missed == PUSH_ACTIVE) return Integer.MAX_VALUE;
        if (missed >= asyncManager.getPollingIntervals().length) {
            return asyncManager.getPollingIntervals()[asyncManager.getPollingIntervals().length - 1];
        }
        return asyncManager.getPollingIntervals()[missed];
    }

    //--- Event listeners

    /**
     * Invoked when a Detach event has been fired.
     *
     * @param ignore component event
     */
    private void onDetachEvent(DetachEvent ignore) {
        // cancel deregisters all listeners via remove()
        cancel();
    }

    /**
     * Invoked when a BeforeLeave event has been fired.
     *
     * @param ignore component event
     */
    private void onBeforeLeaveEvent(BeforeLeaveEvent ignore) {
        // cancel deregisters all listeners
        cancel();
    }

    /**
     * Invoked when a Poll event has been fired.
     *
     * @param ignore component event
     */
    private void onPollEvent(PollEvent ignore) {
        if (missedPolls != PUSH_ACTIVE) {
            missedPolls++;
            asyncManager.adjustPollingInterval(parentUI);
        }
    }
}
