package org.vaadin.flow.helper;

import com.vaadin.flow.component.Component;

/**
 * Functional interface for asynchronous action
 *
 * @author Artem Godin
 * @see AsyncManager#register(Component, Action)
 */
@FunctionalInterface
public interface Action {
    /**
     * Performs this operation on the given argument.
     *
     * @param t the input argument
     */
    void run(Task t) throws Exception;

}
