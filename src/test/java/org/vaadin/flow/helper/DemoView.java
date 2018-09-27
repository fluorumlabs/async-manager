package org.vaadin.flow.helper;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.html.Label;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

@Route("")
public class DemoView extends VerticalLayout implements BeforeEnterObserver {

    // Poll 5 times first second, 2 time second, and once per second afterwards
    public DemoView() {
        AsyncManager.setPollingIntervals(200, 200, 200, 200, 200, 500, 500, 1000);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        attachEvent.getUI().addPollListener(pollEvent -> {
            long newTimestamp = System.currentTimeMillis();
            add(new Label(String.format("POLLING: %d milliseconds has passed...", newTimestamp - lastTimestamp)));
            lastTimestamp = newTimestamp;
        });
    }

    /**
     * Method called before navigation to attaching Component chain is made.
     *
     * @param event before navigation event with event details
     */
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        lastTimestamp = System.currentTimeMillis();

        removeAll();

        add(new RouterLink("Refresh", DemoView.class));
        add(new Label("Waiting for asynchronous tasks..."));

        AsyncManager.register(this, asyncTask -> {
            Thread.sleep(1000);
            asyncTask.push(() -> add(new Label("ASYNC TASK: 1 second has passed")));
        });

        AsyncManager.register(this, asyncTask -> {
            Thread.sleep(2000);
            asyncTask.push(() -> add(new Label("ASYNC TASK: 2 seconds has passed")));
        });

        AsyncManager.register(this, asyncTask -> {
            Thread.sleep(500);
            asyncTask.push(() -> add(new Label("ASYNC TASK: 0.5 seconds has passed")));
        });

        AsyncManager.register(this, asyncTask -> {
            Thread.sleep(5000);
            asyncTask.push(() -> add(new Label("ASYNC TASK: 5 seconds has passed")));
        });
    }

    private long lastTimestamp;
}
