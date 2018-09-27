# Async Manager for Vaadin Flow

In complex application quite often you end up having a view that takes ages to load
because some parts of view require heavy computation. If you are pursuing the goal 
of making your application responsive, you would probably want to defer updates for
these parts. It's quite tempting to use `UI.access()` for that:
```java
...
ui.access(() -> {
    SomeData result = doHeavyLifting();
    showData(result);
});
```
While it will make load time much faster, there is a problem there: since the UI is
blocked by computation thread, user will have to wait for finish before his actions 
will be processed. That is, he will see the same loading indicator if he, for example,
click some button that has server side click listener.

Practically that means that you have to create a worker thread yourself and then call
`UI.accessSynchronously()` when done:
```java
Thread workerThread = new Thread(() -> {
    SomeData result = doHeavyLifting();
    ui.accessSynchronously(() -> showData(result));
});
workerThread.start();
```
That will work with Push out of the box. However, if you don't have Push enabled, you'll
have to call `ui.setPollingInterval()` to enable polling:
```java
ui.setPollingInterval(200);
Thread workerThread = new Thread(() -> {
    SomeData result = doHeavyLifting();
    ui.accessSynchronously(() -> {
        showData(result);
        ui.setPollingInterval(-1); // Disable polling afterwards
    });
});
workerThread.start();
```
Now we have another tiny problem: we need to clear polling interval if the user is 
leaving the view before thread finishes:
```java
ui.setPollingInterval(200);
Thread workerThread = new Thread(() -> {
    SomeData result = doHeavyLifting();
    ui.accessSynchronously(() -> {
        showData(result);
        ui.setPollingInterval(-1); // Disable polling afterwards
    });
});
workerThread.start();
addBeforeLeaveListener(event -> ui.setPollingInterval(-1));
```
See how easy our 4 line snippet turns to 10 line monster? And what if we have 
several of those worker threads? Moreover, even if we have push enabled, something 
should terminate threads when it's result is not needed anymore (i.e. user has left the view).

But wander no more, there is an easy solution: **Async Manager**. It is really easy to use:
```java
AsyncManager.register(this, asyncTask -> {
    SomeData result = doHeavyLifting();
    asyncTask.push(() -> showData(result));
})
```
AsyncManager supports both push and polling, and takes care of cleanup and thread 
termination. For polling mode it also supports
dynamic polling intervals: i.e. you can have 5 polls per second in the
first second and then throttle it to send poll requests once per second:
```java
AsyncManager.setPollingIntervals(200,200,200,200,200,1000);
```

It is also possible to set custom exception handler if you
want some custom logging or exception reporting:
```java
AsyncManager.setExceptionHandler(exception -> ...);
```

By default all worker threads are started by `ThreadPoolExecutor` which defaults
to pool size of 25 threads. You can access instance of executor with 
`AsyncManager.getExecutor()`.

## Development instructions

Starting the test/demo server:
```
mvn jetty:run
```

This deploys demo at http://localhost:8080
