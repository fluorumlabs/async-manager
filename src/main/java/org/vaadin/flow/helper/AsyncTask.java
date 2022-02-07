package org.vaadin.flow.helper;

import com.vaadin.flow.component.*;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.shared.communication.PushMode;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous task created by {@link AsyncManager#register(Component, Action)}
 *
 * @author Artem Godin
 * @see AsyncManager
 */
public class AsyncTask extends Task {
    //--- Defaults

    /**
     * Value for {@link #missedPolls} for push enabled tasks
     */
    private static final int PUSH_ACTIVE = -1;

    //--- Fields

    /**
     * {@link FutureTask} representing action
     */
    private FutureTask<AsyncTask> task;
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
    private final AtomicInteger missedPolls = new AtomicInteger();
    /**
     * {@code true}, if thread may be interrupted if UI/Component detaches
     */
    private boolean mayInterrupt = true;

    /**
     * Create a new task
     */
    AsyncTask(AsyncManager asyncManager) {
        super(asyncManager);
    }

    //--- Public methods

    /**
     * Perform command in UI context. It uses {@link UI#accessSynchronously(Command)} internally.
     *
     * @param command Command to run
     */
    public void push(Command command) {
        if (getUI() == null) {
            return;
        }
        boolean mustPush = missedPolls.get() == PUSH_ACTIVE && getUI().getPushConfiguration().getPushMode() == PushMode.MANUAL;
        getUI().accessSynchronously(() -> {
            try {
                command.execute();
                if (mustPush) {
                    getUI().push();
                }
            } catch (UIDetachedException ignore) {
                // Do not report
                // How could this even happen?
            } catch (Exception e) {
                // Dump
                getAsyncManager().handleException(this, e);
            }
        });
    }

    /**
     * Cancel and unregister the task. Thread interruption behaviour is controlled
     * by {@link AsyncTask#allowThreadInterrupt()} and
     * {@link AsyncTask#preventThreadInterrupt()} methods.
     */
    public void cancel() {
        if (!task.isCancelled() && !task.isDone()) {
            task.cancel(mayInterrupt);
        }
        getAsyncManager().handleTaskStateChanged(this, AsyncManager.TaskStateHandler.State.CANCELED);
        remove();
    }

    /**
     * Wait for the task to finish.
     *
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public void await() throws ExecutionException, InterruptedException {
        task.get();
    }

    /**
     * Allow worker thread to be interrupted when UI or Component detaches. Default behaviour.
     */
    public void allowThreadInterrupt() {
        this.mayInterrupt = true;
    }

    /**
     * Prevent worker thread interruption when UI or Component detaches.
     */
    public void preventThreadInterrupt() {
        this.mayInterrupt = false;
    }

    //--- Implementation

    /**
     * Register action
     *
     * @param ui     UI owning current view
     * @param action Action
     */
    public void register(UI ui, Component component, Action action) {
        setUI(ui);
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
    private void registerPush(Component component, Action action) {
        add();
        missedPolls.set(PUSH_ACTIVE);

        task = createFutureTask(action);

        componentDetachListenerRegistration = component.addDetachListener(this::onDetachEvent);
        uiDetachListenerRegistration = getUI().addDetachListener(this::onDetachEvent);
        beforeLeaveListenerRegistration = getUI().addBeforeLeaveListener(this::onBeforeLeaveEvent);

        execute();
    }

    /**
     * Register action for polling
     *
     * @param action Action
     */
    private void registerPoll(Component component, Action action) {
        add();

        task = createFutureTask(action);

        pollingListenerRegistration = getUI().addPollListener(this::onPollEvent);

        uiDetachListenerRegistration = getUI().addDetachListener(this::onDetachEvent);
        componentDetachListenerRegistration = component.addDetachListener(this::onDetachEvent);
        beforeLeaveListenerRegistration = getUI().addBeforeLeaveListener(this::onBeforeLeaveEvent);

        getAsyncManager().adjustPollingInterval(getUI());
        execute();
    }

    /**
     * Wrap action with {@link FutureTask}
     *
     * @param action Action
     * @return Action wrapped with exception handling
     */
    private FutureTask<AsyncTask> createFutureTask(Action action) {
        return new FutureTask<>(() -> {
            try {
                getAsyncManager().handleTaskStateChanged(this, AsyncManager.TaskStateHandler.State.RUNNING);

                // Session + Security für den Async-Task setzen
                VaadinSession.setCurrent(getUI().getSession());
                SecurityContextHolder.setContext((SecurityContext) VaadinSession.getCurrent().getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));

                action.run(this);
            } catch (UIDetachedException ignore) {
                // Do not report
            } catch (InterruptedException e) {
                // Interrupt current thread
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Dump
                getAsyncManager().handleException(this, e);
            } finally {
                getAsyncManager().handleTaskStateChanged(this, AsyncManager.TaskStateHandler.State.DONE);
                remove();
            }
        }, this);
    }

    /**
     * Execute task in {@link AsyncManager#getExecutorService()} executor
     */
    private void execute() {
        getAsyncManager().getExecutorService().execute(task);
    }

    /**
     * Add current task to {@link AsyncManager#asyncTasks}
     */
    private void add() {
        getAsyncManager().addAsyncTask(getUI(), this);
    }

    /**
     * Remove current task from {@link AsyncManager#asyncTasks} and unregister all listeners
     */
    private void remove() {
        if (getUI() != null) {
            getAsyncManager().removeAsyncTask(getUI(), this);
            // Polling interval needs to be adjusted if task is finished
            try {
                getUI().accessSynchronously(() -> {
                    getAsyncManager().adjustPollingInterval(getUI());

                    if (componentDetachListenerRegistration != null) {
                        componentDetachListenerRegistration.remove();
                    }
                    if (uiDetachListenerRegistration != null) {
                        uiDetachListenerRegistration.remove();
                    }
                    if (pollingListenerRegistration != null) {
                        pollingListenerRegistration.remove();
                    }
                    if (beforeLeaveListenerRegistration != null) {
                        beforeLeaveListenerRegistration.remove();
                    }

                    componentDetachListenerRegistration = null;
                    uiDetachListenerRegistration = null;
                    pollingListenerRegistration = null;
                    beforeLeaveListenerRegistration = null;
                });
            } catch (UIDetachedException ignore) {
                // ignore detached ui -- there will be no polling events for them anyway
            }

            setUI(null);
        }
    }

    /**
     * Get current polling interval based on {@link #missedPolls} and {@link AsyncManager#pollingIntervals}
     *
     * @return Polling interval in milliseconds
     */
    int getPollingInterval() {
        int missed = missedPolls.get();
        if (missed == PUSH_ACTIVE) {
            return Integer.MAX_VALUE;
        }
        if (missed >= getAsyncManager().getPollingIntervals().length) {
            return getAsyncManager().getPollingIntervals()[getAsyncManager().getPollingIntervals().length - 1];
        }
        return getAsyncManager().getPollingIntervals()[missed];
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
        if (missedPolls.get() != PUSH_ACTIVE) {
            missedPolls.incrementAndGet();
            getAsyncManager().adjustPollingInterval(getUI());
        }
    }
}
