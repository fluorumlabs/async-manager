package org.vaadin.flow.helper;

import com.vaadin.flow.component.Component;

/**
 * Functional interface for asynchronous action
 *
 * @author Artem Godin
 * @see AsyncManager#register(Component, AsyncAction)
 */
@FunctionalInterface
public interface AsyncAction {
    /**
     * Performs this operation on the given argument.
     *
     * @param t the input argument
     */
    void run(AsyncTask t) throws Exception;

}
