package org.vaadin.flow.helper;

import com.vaadin.flow.component.*;
import com.vaadin.flow.server.Command;

import java.util.concurrent.ExecutionException;

/**
 * Asynchronous task created by {@link AsyncManager#register(Component, Action)}
 *
 * @author Artem Godin
 * @see AsyncManager
 */
public abstract class Task {
    /**
     * Intance of AsyncManager handling this task
     */
    private final AsyncManager asyncManager;

    /**
     * Owning UI for current task
     */
    private UI parentUI;

    /**
     * Create a new task
     */
    Task(AsyncManager asyncManager) {
        this.asyncManager = asyncManager;
    }

    /**
     * Perform command in UI context.
     *
     * @param command Command to run
     */
    public abstract void push(Command command);

    /**
     * Cancel and unregister the task.
     */
    public abstract void cancel();

    /**
     * Get instance of UI with which this task is associated
     *
     * @return UI instance or {@code null} if task was cancelled or has finished
     * the execution
     */
    public UI getUI() {
        return parentUI;
    }

    /**
     * Wait for the task to finish.
     *
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public abstract void await() throws ExecutionException, InterruptedException;

    /**
     * Allow worker thread to be interrupted when UI or Component detaches. Default behaviour.
     */
    public abstract void allowThreadInterrupt();

    /**
     * Prevent worker thread interruption when UI or Component detaches.
     */
    public abstract void preventThreadInterrupt();


    /**
     * Set instance of UI with which this task is associated
     *
     * @param ui UI instance
     */
    void setUI(UI ui) {
        parentUI = ui;
    }

    /**
     * Get AsyncManager handling this task
     *
     * @return AsyncManager instance
     */
    AsyncManager getAsyncManager() {
        return asyncManager;
    }

    /**
     * Register action
     *
     * @param ui     UI owning current view
     * @param action Action
     */
    abstract void register(UI ui, Component component, Action action);

}
