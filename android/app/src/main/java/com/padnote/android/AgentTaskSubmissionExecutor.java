package com.padnote.android;

import android.content.Context;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.Executors;

/** Process-wide submission lane, independent of Activity-owned AI chat workers. */
final class AgentTaskSubmissionExecutor {
    private static final ExecutorService EXECUTOR=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"padnote-agent-submit");thread.setDaemon(true);return thread;
    });
    private static final ThreadPoolExecutor PREPARATION=new ThreadPoolExecutor(1,1,0,
            TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{
        Thread thread=new Thread(r,"padnote-agent-task-prepare");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());
    private static final Set<String> IN_FLIGHT=ConcurrentHashMap.newKeySet();
    private static final Set<String> ACTIVE=ConcurrentHashMap.newKeySet();
    enum Phase { QUEUED, CONNECTING, UPLOADING, WAITING_FOR_CONFIRMATION, COMPUTER_REJECTED, RESULT_UNCONFIRMED, COMPUTER_ACCEPTED }
    static final class ProgressInfo {
        final Phase phase;final long sent,total,updatedAt;
        ProgressInfo(Phase phase,long sent,long total){this.phase=phase;this.sent=sent;this.total=total;this.updatedAt=System.currentTimeMillis();}
        String bytes(){return sent+"/"+total;}
    }
    private static final ConcurrentHashMap<String,ProgressInfo> PROGRESS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,AtomicLong> STATE_VERSIONS=new ConcurrentHashMap<>();
    private AgentTaskSubmissionExecutor() {}

    static boolean execute(String taskId,Runnable work) {
        if(taskId==null||!IN_FLIGHT.add(taskId))return false;
        setProgress(taskId,Phase.QUEUED,0,0);
        try { EXECUTOR.execute(()->{ACTIVE.add(taskId);setProgress(taskId,Phase.CONNECTING,0,0);try{work.run();}finally{ACTIVE.remove(taskId);IN_FLIGHT.remove(taskId);STATE_VERSIONS.computeIfAbsent(taskId,k->new AtomicLong()).incrementAndGet();trimState();}});return true; }
        catch(RuntimeException error){IN_FLIGHT.remove(taskId);throw error;}
    }

    static boolean isInFlight(String taskId){return IN_FLIGHT.contains(taskId);}
    static boolean isActive(String taskId){return ACTIVE.contains(taskId);}
    static Phase visibleProgressPhase(boolean inFlight,Phase phase){
        if(!inFlight||phase==null)return null;
        switch(phase){
            case QUEUED:case CONNECTING:case UPLOADING:case WAITING_FOR_CONFIRMATION:return phase;
            default:return null;
        }
    }
    static boolean prepare(Runnable work){try{PREPARATION.execute(work);return true;}catch(java.util.concurrent.RejectedExecutionException busy){return false;}}
    static void setProgress(String taskId,Phase phase,long sent,long total){PROGRESS.put(taskId,new ProgressInfo(phase,sent,total));trimState();}
    static ProgressInfo progressInfo(String taskId){return PROGRESS.get(taskId);}
    static String progress(String taskId){ProgressInfo p=PROGRESS.get(taskId);return p==null||p.total<=0?"":p.bytes();}
    static boolean awaitingConfirmation(String taskId){ProgressInfo p=PROGRESS.get(taskId);return p!=null&&p.phase==Phase.WAITING_FOR_CONFIRMATION;}
    static long stateVersion(String taskId){AtomicLong v=STATE_VERSIONS.get(taskId);return v==null?0L:v.get();}

    private static void trimState(){
        while(PROGRESS.size()>128){String id=PROGRESS.keys().nextElement();if(!IN_FLIGHT.contains(id))PROGRESS.remove(id);else break;}
        while(STATE_VERSIONS.size()>256){String id=STATE_VERSIONS.keys().nextElement();if(!IN_FLIGHT.contains(id))STATE_VERSIONS.remove(id);else break;}
    }

    static boolean submit(AgentTaskStore.Task task,AgentTaskStore store,
                          AgentTaskDialogs.TaskConnectionSource connections,
                          AgentTaskClient.Factory clients,Context appContext) {
        return submit(task.clientTaskId,store,connections,clients,appContext);
    }

    static boolean submit(String clientTaskId,AgentTaskStore store,
                          AgentTaskDialogs.TaskConnectionSource connections,
                          AgentTaskClient.Factory clients,Context appContext) {
        return execute(clientTaskId,()->submitStored(clientTaskId,null,false,store,connections,clients,appContext));
    }

    static boolean recoverSameTask(AgentTaskStore.Task expected,boolean bridgeRecovery,
                                   AgentTaskStore store,
                                   AgentTaskDialogs.TaskConnectionSource connections,
                                   AgentTaskClient.Factory clients,Context appContext) {
        if(expected==null)return false;
        return execute(expected.clientTaskId,()->submitStored(expected.clientTaskId,expected,
                bridgeRecovery,store,connections,clients,appContext));
    }

    private static void submitStored(String clientTaskId,AgentTaskStore.Task expected,
                                    boolean bridgeRecovery,AgentTaskStore store,
                                    AgentTaskDialogs.TaskConnectionSource connections,
                                    AgentTaskClient.Factory clients,Context appContext) {
        AgentTaskStore.Task task=store.get(clientTaskId);
        if(!canSubmitStored(task,expected,bridgeRecovery,store.submissionAttemptState(clientTaskId)))return;
        try {
                if(!AgentTaskStore.sha256(task.submissionJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .equalsIgnoreCase(task.submissionSha256))throw new IllegalStateException("Saved task payload checksum did not match");
                AgentConnectionStore.Config connection=connections.get(task.connectionId);
                if(!task.matches(connection)){
                    store.markLocal(task.clientTaskId,AgentTaskStore.Status.INTERRUPTED,
                            appContext.getString(R.string.flow_identity_changed_new));
                    return;
                }
                AgentTaskStore.Task beforeAttempt=store.get(clientTaskId);
                if(!canSubmitStored(beforeAttempt,expected,bridgeRecovery,
                        store.submissionAttemptState(clientTaskId)))return;
                task=beforeAttempt;
                store.markSubmissionAttempted(clientTaskId);
                AgentTaskStore.Task immediatelyBeforeNetwork=store.get(clientTaskId);
                if(!sameSubmissionSnapshot(task,immediatelyBeforeNetwork)||
                        immediatelyBeforeNetwork.status!=AgentTaskStore.Status.SUBMITTING||
                        !immediatelyBeforeNetwork.remoteTaskId.isEmpty())return;
                AgentConnectionStore.Config currentConnection=connections.get(task.connectionId);
                if(!task.matches(currentConnection))return;
                AgentTaskClient.Submission result=clients.create().submit(immediatelyBeforeNetwork,currentConnection,
                        new AgentHttpTransport.Progress(){
                            @Override public void onUploadStarted(long total){setProgress(clientTaskId,Phase.UPLOADING,0,total);}
                            @Override public void onBytesWritten(long sent,long total){setProgress(clientTaskId,Phase.UPLOADING,sent,total);}
                            @Override public void onWaitingForResponse(long total){setProgress(clientTaskId,Phase.WAITING_FOR_CONFIRMATION,total,total);}
                        });
                AgentConnectionStore.Config latest=connections.get(task.connectionId);
                if(!immediatelyBeforeNetwork.matches(latest))throw new IllegalStateException(
                        appContext.getString(R.string.flow_task_changed_during_submit));
                boolean applied=store.applySubmission(task.clientTaskId,task.connectionId,task.connectionRevision,
                        result.remoteTaskId,result.status,result.conversationId,result.parentTaskId);
                if(!applied)throw new IllegalStateException("Computer accepted the task but local confirmation could not be saved");
                ProgressInfo before=PROGRESS.get(clientTaskId);setProgress(clientTaskId,Phase.COMPUTER_ACCEPTED,
                        before==null?0:before.sent,before==null?0:before.total);
        } catch(Exception error) {
                try {
                    if(error instanceof AgentTaskClient.HttpStatusException)
                        store.markFailed(task.clientTaskId,task.updatedAt,safeMessage(error));
                    else store.markError(task.clientTaskId,task.updatedAt,
                            appContext.getString(R.string.flow_submit_unknown,safeMessage(error)));
                } catch(Exception ignored) { }
                ProgressInfo before=PROGRESS.get(clientTaskId);
                setProgress(clientTaskId,error instanceof AgentTaskClient.HttpStatusException?
                                Phase.COMPUTER_REJECTED:Phase.RESULT_UNCONFIRMED,
                        before==null?0:before.sent,before==null?0:before.total);
        }
    }

    static boolean canSubmitStored(AgentTaskStore.Task task,AgentTaskStore.Task expected,
                                   boolean bridgeRecovery,int attemptState) {
        if(task==null||task.status!=AgentTaskStore.Status.SUBMITTING||!task.remoteTaskId.isEmpty())return false;
        if(expected==null)return attemptState==0;
        if(!sameSubmissionSnapshot(expected,task))return false;
        if(bridgeRecovery)return AgentTaskDialogs.canOfferSameTaskRecovery(task,attemptState,false);
        return AgentTaskDialogs.canSendSavedTask(task,attemptState,false);
    }

    static boolean sameSubmissionSnapshot(AgentTaskStore.Task first,AgentTaskStore.Task second) {
        return first!=null&&second!=null&&first.clientTaskId.equals(second.clientTaskId)&&
                first.connectionId.equals(second.connectionId)&&first.connectionRevision==second.connectionRevision&&
                first.origin.equals(second.origin)&&first.kind==second.kind&&first.transport==second.transport&&
                first.credentialRef.equals(second.credentialRef)&&first.bridgeId.equals(second.bridgeId)&&
                first.instanceId.equals(second.instanceId)&&first.noteId.equals(second.noteId)&&
                first.noteRevision==second.noteRevision&&first.submissionJson.equals(second.submissionJson)&&
                first.submissionSha256.equalsIgnoreCase(second.submissionSha256);
    }

    private static String safeMessage(Throwable error){
        String message=error.getMessage()==null?"Unknown error":error.getMessage();
        message=message.replaceAll("(?i)(bearer\\s+)[^\\s,;]+","$1[redacted]")
                .replaceAll("[\\r\\n]"," ");
        return message.length()>500?message.substring(0,500):message;
    }
}
