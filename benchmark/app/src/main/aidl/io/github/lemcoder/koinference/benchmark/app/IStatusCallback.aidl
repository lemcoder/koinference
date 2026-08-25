package io.github.lemcoder.koinference.benchmark.app;

/** Whether something the app asked a backend to do worked. */
interface IStatusCallback {

    /** Carries whatever the operation produced, as JSON; an empty object when it produced nothing. */
    oneway void onReady(String infoJson);

    oneway void onFailed(String message);
}
