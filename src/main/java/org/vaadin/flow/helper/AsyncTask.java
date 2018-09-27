package org.vaadin.flow.helper;

import com.vaadin.flow.component.*;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.shared.communication.PushMode;

import java.util.concurrent.FutureTask;

/**
 * Asynchronous task created by {@link AsyncManager#register(Component, AsyncAction)} or
 * {@link AsyncManager#register(Component, AsyncAction)}.
 *
 * @author Artem Godin
 * @see AsyncManager
 */
public class AsyncTask {
    /**
     * Create a new task
     */
    AsyncTask() {
    }

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

    /**
     * Execute task in {@link AsyncManager#getExecutor()} executor
     */
    private void execute() {
        AsyncManager.getExecutor().execute(task);
    }

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

        AsyncManager.adjustPollingInterval(parentUI);
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
                AsyncManager.handleException(e);
            } finally {
                remove();
            }
        }, this);
    }

    /**
     * Add current task to {@link AsyncManager#asyncTasks}
     */
    private void add() {
        AsyncManager.addAsyncTask(parentUI, this);
    }

    /**
     * Remove current task from {@link AsyncManager#asyncTasks} and unregister all listeners
     */
    private synchronized void remove() {
        if (parentUI != null) {
            AsyncManager.removeAsyncTask(parentUI, this);
            // Polling interval needs to be adjusted if task is finished
            try {
                parentUI.accessSynchronously(() -> {
                    AsyncManager.adjustPollingInterval(parentUI);

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
        if (missed >= AsyncManager.getPollingIntervals().length) {
            return AsyncManager.getPollingIntervals()[AsyncManager.getPollingIntervals().length - 1];
        }
        return AsyncManager.getPollingIntervals()[missed];
    }

    /**
     * Value for {@link #missedPolls} for push enabled tasks
     */
    private static final int PUSH_ACTIVE = -1;

    /**
     * Invoked when a Detach event has been fired.
     *
     * @param event component event
     */
    private void onDetachEvent(DetachEvent event) {
        // cancel deregister all listeners via remove()
        cancel();
    }

    /**
     * Invoked when a BeforeLeave event has been fired.
     *
     * @param event component event
     */
    private void onBeforeLeaveEvent(BeforeLeaveEvent event) {
        // cancel deregister all listeners
        cancel();
    }

    /**
     * Invoked when a Poll event has been fired.
     *
     * @param event component event
     */
    private void onPollEvent(PollEvent event) {
        if (missedPolls != PUSH_ACTIVE) {
            missedPolls++;
            AsyncManager.adjustPollingInterval(parentUI);
        }
    }
}
