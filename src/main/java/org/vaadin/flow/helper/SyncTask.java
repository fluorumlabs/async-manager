package org.vaadin.flow.helper;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;

import java.util.concurrent.ExecutionException;

/**
 * Created by Artem Godin on 3/29/2019.
 */
public class SyncTask extends Task {
    /**
     * Create a new task
     *
     * @param asyncManager
     */
    SyncTask(AsyncManager asyncManager) {
        super(asyncManager);
    }

    /**
     * Perform command in UI context.
     *
     * @param command Command to run
     */
    public void push(Command command) {
        command.execute();
    }

    /**
     * Cancel and unregister the task.
     */
    public void cancel() {
        // no-op
    }

    /**
     * Wait for the task to finish.
     *
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public void await() throws ExecutionException, InterruptedException {
        // no-op
    }

    /**
     * Allow worker thread to be interrupted when UI or Component detaches. Default behaviour.
     */
    public void allowThreadInterrupt() {
        // no-op
    }

    /**
     * Prevent worker thread interruption when UI or Component detaches.
     */
    public void preventThreadInterrupt() {
        // no-op
    }

    /**
     * Register action
     *
     * @param ui        UI owning current view
     * @param component
     * @param action    Action
     */
    void register(UI ui, Component component, Action action) {
        setUI(ui);
        try {
            action.run(this);
        } catch (Exception e) {
            // Dump
            getAsyncManager().handleException(this, e);
        }
    }
}
