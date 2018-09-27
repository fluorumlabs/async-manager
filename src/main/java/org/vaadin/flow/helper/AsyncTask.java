package org.vaadin.flow.helper;

import com.vaadin.flow.component.*;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.shared.Registration;

import java.util.concurrent.FutureTask;

/**
 * Asynchronous task created by {@link AsyncManager#register(Component, AsyncAction)} or
 * {@link AsyncManager#register(Component, boolean, AsyncAction)}.
 *
 * @author Artem Godin
 * @see AsyncManager
 */
public class AsyncTask {
    /**
     * Create a new task
     *
     * @param component Owning component
     */
    AsyncTask(Component component) {
        this.parentComponent = component;
    }

    /**
     * Perform command in {@code VaadinRequest} context. That requires the AsyncTask to be registered
     * in a polling mode. The command will be executed in PollEvent listener meaning that
     * {@link UI#accessSynchronously(Command)} is not needed.
     *
     * @param command Command to run
     */
    public void sync(Command command) {
        if (parentComponent == null) return;
        if (missedPolls == PUSH_ACTIVE) {
            throw new IllegalStateException("Sync is called but Polling Manager is not in polling mode");
        }
        if (syncCommand != null) {
            throw new IllegalStateException("Sync can be used only once");
        }
        syncCommand = command;
    }

    /**
     * Perform command in UI context. It uses {@link UI#accessSynchronously(Command)} internally.
     *
     * @param command Command to run
     */
    public void push(Command command) {
        if (parentComponent == null) return;
        parentComponent.getUI().ifPresent(ui -> {
            if (missedPolls == PUSH_ACTIVE) {
                ui.accessSynchronously(() -> {
                    command.execute();
                    ui.push();
                });
            } else {
                // Automatic -- changes will be pushed automatically
                // Disabled -- we're using polling and this is called
                //             within UIDLRequestHandler
                ui.accessSynchronously(command);
            }
        });
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
     * Owning component for current task
     */
    private Component parentComponent;

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
     * Command that needs to be executed in VaadinRequest context
     */
    private Command syncCommand;

    /**
     * Number of poll events happened while action is executing, or {@link #PUSH_ACTIVE} if
     * push is used for current task
     */
    private int missedPolls = 0;

    /**
     * Register action
     *
     * @param ui           UI owning current view
     * @param forcePolling <tt>true</tt> if polling must be used
     * @param action       Action
     */
    void register(UI ui, boolean forcePolling, AsyncAction action) {
        if (!forcePolling && ui.getPushConfiguration().getPushMode().isEnabled()) {
            registerPush(ui, action);
        } else {
            registerPoll(ui, action);
        }

    }

    /**
     * Register action for push mode
     *
     * @param ui     UI owning current view
     * @param action Action
     */
    private void registerPush(UI ui, AsyncAction action) {
        add();
        missedPolls = PUSH_ACTIVE;

        task = createFutureTask(action);

        componentDetachListenerRegistration = parentComponent.addDetachListener(this::onDetachEvent);
        uiDetachListenerRegistration = ui.addDetachListener(this::onDetachEvent);
        beforeLeaveListenerRegistration = ui.addBeforeLeaveListener(this::onBeforeLeaveEvent);

        execute();
    }

    /**
     * Register action for polling
     *
     * @param ui     UI owning current view
     * @param action Action
     */
    private void registerPoll(UI ui, AsyncAction action) {
        add();

        task = createFutureTask(action);

        pollingListenerRegistration = ui.addPollListener(this::onPollEvent);

        uiDetachListenerRegistration = ui.addDetachListener(this::onDetachEvent);
        componentDetachListenerRegistration = parentComponent.addDetachListener(this::onDetachEvent);
        beforeLeaveListenerRegistration = ui.addBeforeLeaveListener(this::onBeforeLeaveEvent);

        AsyncManager.adjustPollingInterval(parentComponent, ui);
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
                if (syncCommand == null && !Thread.currentThread().isInterrupted()) {
                    remove();
                }
            }
        }, this);
    }

    /**
     * Add current task to {@link AsyncManager#asyncTasks}
     */
    private void add() {
        AsyncManager.addAsyncTask(parentComponent, this);
    }

    /**
     * Remove current task from {@link AsyncManager#asyncTasks} and unregister all listeners
     */
    private synchronized void remove() {
        if (parentComponent != null) {
            Component component = parentComponent;
            parentComponent = null;

            AsyncManager.removeAsyncTask(component, this);

            if (componentDetachListenerRegistration != null) componentDetachListenerRegistration.remove();
            if (uiDetachListenerRegistration != null) uiDetachListenerRegistration.remove();
            if (pollingListenerRegistration != null) pollingListenerRegistration.remove();
            if (beforeLeaveListenerRegistration != null) beforeLeaveListenerRegistration.remove();

            // Polling interval needs to be adjusted if task is finished
            component.getUI().ifPresent(ui -> ui.accessSynchronously(() -> AsyncManager.adjustPollingInterval(component, ui)));
        }
    }

    /**
     * Get current polling interval based on {@link #missedPolls} and {@link AsyncManager#pollingIntervals}
     *
     * @return Polling interval in milliseconds
     */
    int getPollingInterval() {
        if (missedPolls == PUSH_ACTIVE) return Integer.MAX_VALUE;
        if (missedPolls >= AsyncManager.getPollingIntervals().length) {
            return AsyncManager.getPollingIntervals()[AsyncManager.getPollingIntervals().length - 1];
        }
        return AsyncManager.getPollingIntervals()[missedPolls];
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
        if (missedPolls != PUSH_ACTIVE) missedPolls++;
        if (syncCommand != null) {
            try {
                syncCommand.execute();
            } catch (RuntimeException e) {
                AsyncManager.handleException(e);
            }
            remove();
        }
    }
}
