package org.vaadin.flow.helper;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Label;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.communication.PushMode;

@Route("")
public class DemoView extends VerticalLayout implements BeforeEnterObserver {

    private Checkbox pushToggle = new Checkbox("Enable push");
    private long lastTimestamp;

    // Poll 5 times first second, 2 time second, and once per second afterwards
    public DemoView() {
        AsyncManager.getInstance().setPollingIntervals(200, 200, 200, 200, 200, 500, 500, 1000);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        attachEvent.getUI().addPollListener(pollEvent -> {
            long newTimestamp = System.currentTimeMillis();
            add(new Label(String.format("POLLING: %d milliseconds has passed...", newTimestamp - lastTimestamp)));
            lastTimestamp = newTimestamp;
        });
        pushToggle.addValueChangeListener(value -> attachEvent.getUI().getPushConfiguration().setPushMode(value.getValue() ? PushMode.AUTOMATIC : PushMode.DISABLED));
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
        add(pushToggle);
        add(new Label("Waiting for asynchronous tasks..."));

        AsyncTask task = AsyncManager.register(this, asyncTask -> {
            Thread.sleep(1000);
            asyncTask.push(() -> add(new Label("ASYNC TASK: 1 second has passed")));
            asyncTask.push(() -> getUI().get().getSession().close());
        });

        AsyncManager.register(this, asyncTask -> {
            Thread.sleep(2000);
            asyncTask.push(() -> add(new Label("ASYNC TASK: 2 seconds has passed")));
        });

        AsyncManager.register(this, asyncTask -> {
            Thread.sleep(500);
            asyncTask.push(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                add(new Label("ASYNC TASK: 0.5 seconds has passed"));
            });
        });

        AsyncManager.register(this, asyncTask -> {
            Thread.sleep(5000);
            asyncTask.push(() -> add(new Label("ASYNC TASK: 5 seconds has passed")));
        });
    }
}
