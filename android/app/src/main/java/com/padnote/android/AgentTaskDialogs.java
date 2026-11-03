package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.ref.WeakReference;

/** Persistent Agent task list/detail with bounded foreground polling. */
final class AgentTaskDialogs {
    /** Status reads must not wait behind interactive AI work. */
    private static final ExecutorService STATUS_REFRESH_EXECUTOR = new ThreadPoolExecutor(2, 2,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), r -> {
                Thread thread = new Thread(r, "padnote-task-status");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    interface SourceNoteOpener { boolean open(String noteId); }
    interface TaskConnectionSource {
        List<AgentConnectionStore.Config> list();
        AgentConnectionStore.Config get(String id);
    }
    private static final class StoreTaskConnectionSource implements TaskConnectionSource {
        private final AgentConnectionStore store;
        StoreTaskConnectionSource(AgentConnectionStore store){this.store=store;}
        @Override public List<AgentConnectionStore.Config> list(){return store.list();}
        @Override public AgentConnectionStore.Config get(String id){return store.get(id);}
    }
    interface BundleFactory { byte[] make() throws Exception; }
    interface ArtifactSaveLauncher {
        void choose(AgentTaskStore.Task task, AgentTaskStore.Artifact artifact);
    }
    interface ArtifactImagePlacementLauncher { void place(AgentTaskStore.Task task,AgentTaskStore.Artifact artifact); }
    interface ResultAppender { void append(String clientTaskId); }
    private final Activity activity;
    private final AgentConnectionStore connections;
    private final AgentTaskStore tasks;
    private final ExecutorService worker;
    private final ArtifactSaveLauncher artifactSaveLauncher;
    private ArtifactImagePlacementLauncher artifactImagePlacementLauncher;
    private final VideoTaskDialogs.Factory videoDialogFactory;
    private final SourceNoteOpener sourceNoteOpener;
    private final AgentTaskClient.Factory taskClientFactory;
    private final TaskConnectionSource taskConnectionSource;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ResultAppender resultAppender;
    private java.util.function.Consumer<String> lifecycleObserverForTest;

    void setResultAppender(ResultAppender appender) { resultAppender = appender; }
    void setLifecycleObserverForTest(java.util.function.Consumer<String> observer) {
        lifecycleObserverForTest=observer;
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker) {
        this(activity, connections, worker, null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher) {
        this(activity,connections,worker,artifactSaveLauncher,new AgentTaskStore(activity));
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks) {
        this(activity,connections,worker,artifactSaveLauncher,tasks,VideoTaskDialogs::new,null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks,
                     VideoTaskDialogs.Factory videoDialogFactory) {
        this(activity,connections,worker,artifactSaveLauncher,tasks,videoDialogFactory,null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks,
                     VideoTaskDialogs.Factory videoDialogFactory,SourceNoteOpener sourceNoteOpener) {
        this(activity,connections,worker,artifactSaveLauncher,tasks,videoDialogFactory,
                sourceNoteOpener,AgentTaskClient::new);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks,
                     VideoTaskDialogs.Factory videoDialogFactory,SourceNoteOpener sourceNoteOpener,
                     AgentTaskClient.Factory taskClientFactory) {
        this(activity,connections,worker,artifactSaveLauncher,tasks,videoDialogFactory,
                sourceNoteOpener,taskClientFactory,null);
    }

    AgentTaskDialogs(Activity activity, AgentConnectionStore connections, ExecutorService worker,
                     ArtifactSaveLauncher artifactSaveLauncher,AgentTaskStore tasks,
                     VideoTaskDialogs.Factory videoDialogFactory,SourceNoteOpener sourceNoteOpener,
                     AgentTaskClient.Factory taskClientFactory,TaskConnectionSource taskConnectionSource) {
        this.activity=activity;this.connections=connections;this.worker=worker;
        this.artifactSaveLauncher=artifactSaveLauncher;
        this.tasks=tasks;
        this.videoDialogFactory=videoDialogFactory;
        this.sourceNoteOpener=sourceNoteOpener;
        this.taskClientFactory=taskClientFactory==null?AgentTaskClient::new:taskClientFactory;
        this.taskConnectionSource=taskConnectionSource==null?
                new StoreTaskConnectionSource(new AgentConnectionStore(activity.getApplicationContext())):taskConnectionSource;
    }

    void showForNote(String noteId) {
        List<AgentTaskStore.Task> values=new ArrayList<>();
        for(AgentTaskStore.Task task:tasks.list())if(noteId.equals(task.noteId))values.add(task);
        if(values.isEmpty()){ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_history_title))
                .setMessage(activity.getString(R.string.flow_no_video_tasks))
                .setNegativeButton(activity.getString(R.string.flow_close),null).show();return;}
        List<String> labels=new ArrayList<>();for(AgentTaskStore.Task t:values){
            labels.add(historyRow(t)+"\n"+activity.getString(R.string.flow_source_revision_value,t.noteRevision));}
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_history_title))
                .setAdapter(ChoiceRows.adapter(activity, labels.toArray(new String[0])),
                        (d,w)->showDetail(values.get(w).clientTaskId))
                .setNegativeButton(activity.getString(R.string.flow_close),null).show();
    }

    void showList() {
        List<AgentTaskStore.Task> values=tasks.list();
        int corruptCount=tasks.corruptCount();
        if(values.isEmpty()){
            String message=activity.getString(R.string.flow_task_list_empty);
            if(corruptCount>0)message+="\n"+activity.getString(R.string.flow_corrupt_records_tail,corruptCount);
            ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_computer_tasks)).setMessage(message)
                    .setNegativeButton(activity.getString(R.string.flow_close),null)
                    .setPositiveButton(activity.getString(R.string.flow_choose_add_connection),(dialog,which)->
                            new AgentConnectionDialogs(activity,connections,worker,()->{},null,
                                    artifactSaveLauncher).show())
                    .show();
            return;
        }
        List<String> labels=new ArrayList<>();
        if(corruptCount>0)labels.add(activity.getString(R.string.flow_corrupt_records_warning,corruptCount));
        int warningRows=labels.size();
        for(AgentTaskStore.Task task:values)labels.add(historyRow(task)+
                (task.error.isEmpty()?"":"\n"+task.error));
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_computer_tasks))
                .setAdapter(multilineRows(labels),(d,w)->{if(w>=warningRows)
                    showDetail(values.get(w-warningRows).clientTaskId);})
                .setNegativeButton(activity.getString(R.string.flow_close),null).show();
    }

    private String historyRow(AgentTaskStore.Task task) {
        String row=task.title+"\n"+taskStatus(task);
        String delivery=historyDeliveryCue(task);
        if(!delivery.isEmpty())row+="\n"+delivery;
        return row+"\n"+activity.getString(R.string.flow_task_target_line,
                AgentTaskDestinationLabel.forTask(activity,task));
    }

    private String historyDeliveryCue(AgentTaskStore.Task task) {
        if(task==null||task.status!=AgentTaskStore.Status.COMPLETED||task.delivery==null||
                task.expectedOutputs.isEmpty())return "";
        AgentDelivery.Summary summary=task.delivery;
        if(summary.status==AgentDelivery.Status.MISSING)
            return activity.getString(R.string.flow_history_delivery_missing,
                    deliveryTypeList(summary.missing));
        if(summary.status==AgentDelivery.Status.DELIVERED)
            return activity.getString(R.string.flow_history_delivery_delivered,
                    deliveryTypeList(summary.expected));
        return "";
    }

    private ArrayAdapter<String> multilineRows(List<String> labels) {
        return new ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,labels) {
            @Override public View getView(int position,View convertView,ViewGroup parent) {
                TextView row=(TextView)super.getView(position,convertView,parent);
                row.setSingleLine(false);
                row.setMaxLines(Integer.MAX_VALUE);
                row.setEllipsize(null);
                return row;
            }
        };
    }

    void showNewText(AgentConnectionStore.Config profile) {
        EditText input=new EditText(activity);input.setHint(activity.getString(R.string.flow_text_task_hint));
        input.setMinLines(6);input.setGravity(android.view.Gravity.TOP);input.setInputType(
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        DeliverySelector selector=new DeliverySelector(activity,Collections.emptyList(),
                AgentDelivery.contractSupported(profile));
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);
        form.addView(input);form.addView(selector.view());
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_send_text_to,destinationLabel(activity, profile))).setView(scroll(form))
                .setNegativeButton(activity.getString(R.string.flow_cancel),null).setPositiveButton(activity.getString(R.string.flow_send),(d,w)->{
                    String text=input.getText().toString().trim();
                    if(text.isEmpty()){toast(activity.getString(R.string.flow_empty_task));return;}
                    createAndSubmit(profile,activity.getString(R.string.flow_plain_text_task),text,"",1,null,
                            "",selector.selected());
                }).show();
    }

    void chooseAndSubmitBundle(String title,String input,String noteId,long noteRevision,
                               BundleFactory factory) {
        List<AgentConnectionStore.Config> profiles=new ArrayList<>();
        for(AgentConnectionStore.Config c:taskConnectionSource.list())if(c.verified()&&
                c.transport==AgentConnectionStore.Transport.BRIDGE&&c.capabilities.contains("task_bundle")&&
                (c.kind!=AgentConnectionStore.Kind.BUILTIN_VIDEO||c.capabilities.contains("video_task_submission")))profiles.add(c);
        if(profiles.isEmpty()){toast(activity.getString(R.string.flow_no_bridge));return;}
        String[] labels=new String[profiles.size()];for(int i=0;i<labels.length;i++)labels[i]=destinationLabel(activity, profiles.get(i));
        ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_choose_agent)).setAdapter(ChoiceRows.adapter(activity, labels),(d,w)->{
                    AgentConnectionStore.Config selected=profiles.get(w);
                    DeliverySelector selector=new DeliverySelector(activity,Collections.singletonList("video"),
                            AgentDelivery.contractSupported(selected));
                    LinearLayout confirmation=new LinearLayout(activity);confirmation.setOrientation(LinearLayout.VERTICAL);
                    TextView description=new TextView(activity);description.setText(
                            activity.getString(R.string.flow_target)+": "+destinationLabel(activity, selected)+"\n"+
                                    activity.getString(R.string.flow_material)+": "+title+
                                    activity.getString(R.string.flow_confirm_package_body));
                    confirmation.addView(description);confirmation.addView(selector.view());
                    ChoiceRows.builder(activity).setTitle(activity.getString(R.string.flow_confirm_package))
                            .setView(scroll(confirmation))
                            .setNegativeButton(activity.getString(R.string.flow_cancel),null).setPositiveButton(activity.getString(R.string.flow_send_to_agent),(confirm,which)->
                                    worker.execute(()->{try{byte[] bundle=factory.make();main.post(()->
                                            createAndSubmit(selected,title,input,noteId,noteRevision,bundle,
                                                    "",selector.selected()));}
                                        catch(Exception e){main.post(()->toast(activity.getString(R.string.flow_package_failed,message(e))));}})).show();
                })
                .setNegativeButton(activity.getString(R.string.flow_cancel),null).show();
    }

    void sendNoteWork(AgentConnectionStore.Config selected,NoteWorkBundleIO.Frozen frozen,
                      String title,String input,String noteId) {
        sendNoteWork(selected,frozen,title,input,noteId,Collections.emptyList());
    }

    void sendNoteWork(AgentConnectionStore.Config selected,NoteWorkBundleIO.Frozen frozen,
                      String title,String input,String noteId,List<String> requestedOutputs) {
        if(selected==null||!selected.verified()||selected.transport!=AgentConnectionStore.Transport.BRIDGE||
                selected.featureMap==null||
                selected.kind==AgentConnectionStore.Kind.BUILTIN_VIDEO||
                !Boolean.TRUE.equals(selected.featureMap.get("task_bundle"))||
                !Boolean.TRUE.equals(selected.featureMap.get("note_context_bundle"))||
                !Boolean.TRUE.equals(selected.featureMap.get("run_submission"))) {
            toast(activity.getString(R.string.flow_task_package_unsupported));return;
        }
        List<String> expectedOutputs=AgentDelivery.normalize(requestedOutputs);
        final String baseTaskInput=input;
        WeakReference<AgentTaskDialogs> dialogRef=new WeakReference<>(this);
        AgentTaskStore taskStore=tasks;
        TaskConnectionSource connectionSource=taskConnectionSource;
        AgentTaskClient.Factory clients=taskClientFactory;
        android.content.Context appContext=activity.getApplicationContext();
        if(!AgentTaskSubmissionExecutor.prepare(()->{
            final AgentTaskStore.Task task;
            try{
                AgentConnectionStore.Config current=connectionSource.get(selected.id);
                if(!selected.sameIdentityRevision(current)||!current.verified())
                    throw new IllegalStateException(appContext.getString(R.string.flow_identity_changed_new));
                String taskInput=baseTaskInput;
                if(!expectedOutputs.isEmpty()&&!AgentDelivery.contractSupported(current))
                    taskInput+="\n\n"+appContext.getString(R.string.flow_delivery_legacy_instruction,
                            deliveryTypeList(expectedOutputs));
                task=taskStore.create(current,title,taskInput,noteId,frozen.noteRevision,
                        frozen.zip.clone(),"note_context_bundle",expectedOutputs);
            }
            catch(Exception error){main.post(()->{AgentTaskDialogs d=dialogRef.get();if(d!=null)d.toast(appContext.getString(R.string.flow_task_create_failed,safeError(error)));});return;}
            AgentTaskSubmissionExecutor.submit(task,taskStore,connectionSource,clients,appContext);
            main.post(()->{AgentTaskDialogs d=dialogRef.get();if(d!=null)d.toast(appContext.getString(R.string.flow_task_submitting));});
        }))toast(activity.getString(R.string.flow_task_queue_busy));
    }

    static String destinationLabel(AgentConnectionStore.Config profile) {
        return AgentTaskDestinationLabel.forProfile(profile);
    }

    static String destinationLabel(android.content.Context context,
                                  AgentConnectionStore.Config profile) {
        return AgentTaskDestinationLabel.forProfile(context, profile);
    }

    private void createAndSubmit(AgentConnectionStore.Config profile,String title,String input,
                                 String noteId,long noteRevision,byte[] bundle) {
        createAndSubmit(profile,title,input,noteId,noteRevision,bundle,"",Collections.emptyList());
    }

    private void createAndSubmit(AgentConnectionStore.Config profile,String title,String input,
                                 String noteId,long noteRevision,byte[] bundle,String requiredCapability) {
        createAndSubmit(profile,title,input,noteId,noteRevision,bundle,requiredCapability,
                Collections.emptyList());
    }

    private void createAndSubmit(AgentConnectionStore.Config profile,String title,String input,
                                 String noteId,long noteRevision,byte[] bundle,String requiredCapability,
                                 List<String> expectedOutputs) {
        final AgentTaskStore.Task task;
        List<String> normalized=AgentDelivery.normalize(expectedOutputs);
        String wireInput=input;
        try{
            AgentConnectionStore.Config current=taskConnectionSource.get(profile.id);
            if(!profile.sameIdentityRevision(current)||!current.verified())
                throw new IllegalStateException(activity.getString(R.string.flow_identity_changed_new));
            if(!normalized.isEmpty()&&!AgentDelivery.contractSupported(current))
                wireInput=input+"\n\n"+activity.getString(R.string.flow_delivery_legacy_instruction,
                        deliveryTypeList(normalized));
            task=tasks.create(current,title,wireInput,noteId,noteRevision,bundle,requiredCapability,normalized);
        }
        catch(Exception e){toast(activity.getString(R.string.flow_task_create_failed_short,message(e)));return;}
        submit(task);
    }

    private void submit(AgentTaskStore.Task task) {
        toast(activity.getString(R.string.flow_task_submitting));
        AgentTaskSubmissionExecutor.submit(task,tasks,taskConnectionSource,taskClientFactory,
                activity.getApplicationContext());
    }

    private static String safeError(Throwable error){String m=error.getMessage();return m==null?"Unknown error":m.replaceAll("[\\r\\n]"," ");}

    private void askFollowup(String parentId,AlertDialog parentDialog) {
        askFollowup(parentId,parentDialog,false);
    }

    private void askFollowup(String parentId,AlertDialog parentDialog,boolean repair) {
        AgentTaskStore.Task parent=tasks.get(parentId);if(parent==null)return;
        if(parent.status!=AgentTaskStore.Status.COMPLETED||!parent.followupAvailable||
                parent.transport!=AgentConnectionStore.Transport.BRIDGE){
            toast(activity.getString(R.string.flow_delivery_repair_unavailable));return;
        }
        List<String> initialKinds=repair?parent.delivery.missing:Collections.emptyList();
        if(repair&&(parent.delivery.status!=AgentDelivery.Status.MISSING||initialKinds.isEmpty()))return;
        AgentConnectionStore.Config initialConnection=taskConnectionSource.get(parent.connectionId);
        if(!parent.matches(initialConnection)){
            toast(activity.getString(R.string.flow_identity_changed_followup));return;
        }
        EditText input=new EditText(activity);input.setHint(activity.getString(R.string.flow_followup_hint));
        input.setMinLines(4);input.setGravity(android.view.Gravity.TOP);input.setInputType(
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if(repair)input.setText(activity.getString(R.string.flow_delivery_repair_prefill,
                deliveryTypeList(initialKinds)));
        DeliverySelector selector=new DeliverySelector(activity,initialKinds,
                AgentDelivery.contractSupported(initialConnection));
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);
        form.addView(input);form.addView(selector.view());
        AlertDialog followupDialog=ChoiceRows.builder(activity).setTitle(activity.getString(repair
                ?R.string.flow_delivery_repair_title:R.string.flow_continue_chat)).setView(scroll(form))
                .setNegativeButton(activity.getString(R.string.flow_cancel),null)
                .setPositiveButton(activity.getString(R.string.flow_send),null).create();
        followupDialog.setOnShowListener(ignored->followupDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v->{
                    String text=input.getText().toString().trim();
                    if(text.isEmpty()){toast(activity.getString(R.string.flow_followup_empty));return;}
                    List<String> expected=selector.selected();
                    if(repair&&!expected.containsAll(initialKinds)){
                        toast(activity.getString(R.string.flow_delivery_repair_empty));return;
                    }
                    AgentTaskStore.Task current=tasks.get(parentId);
                    if(current==null||(repair&&(current.updatedAt!=parent.updatedAt||
                            current.delivery.status!=AgentDelivery.Status.MISSING||
                            !current.delivery.missing.equals(initialKinds)))){
                        followupDialog.dismiss();
                        toast(activity.getString(R.string.flow_task_changed_during_submit));return;
                    }
                    AgentConnectionStore.Config connection=taskConnectionSource.get(parent.connectionId);
                    if(!parent.matches(connection)){
                        followupDialog.dismiss();toast(activity.getString(R.string.flow_identity_changed_followup));return;
                    }
                    String wireText=text;
                    if(!expected.isEmpty()&&!AgentDelivery.contractSupported(connection))
                        wireText=text+"\n\n"+activity.getString(R.string.flow_delivery_legacy_instruction,
                                deliveryTypeList(expected));
                    try {
                        boolean childAlreadySaved=!tasks.children(parentId).isEmpty();
                        AgentTaskStore.Task child=tasks.createFollowup(parent,connection,wireText,expected);
                        followupDialog.dismiss();parentDialog.dismiss();
                        if(child.status==AgentTaskStore.Status.SUBMITTING&&!childAlreadySaved)submit(child);
                        else showDetail(child.clientTaskId);
                    } catch(Exception e){
                        List<AgentTaskStore.Task> existing=tasks.children(parentId);
                        if(!existing.isEmpty()&&message(e).contains(activity.getString(R.string.flow_new_text_not_sent))){
                            toast(activity.getString(R.string.flow_followup_saved_not_sent));
                            followupDialog.dismiss();parentDialog.dismiss();showDetail(existing.get(0).clientTaskId);
                        }else toast(activity.getString(R.string.flow_continue_failed,message(e)));
                    }
                }));
        followupDialog.show();
    }

    AlertDialog showDetail(String clientId) {
        AgentTaskStore.Task initial=tasks.get(clientId);if(initial==null){observeLifecycleForTest("missing_task");toast(activity.getString(R.string.flow_record_missing));return null;}
        final boolean videoBundleValid=VideoTaskClient.isVideoBundle(initial);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20),dp(8),dp(20),dp(8));
        LinearLayout statusCard=paperCard();
        TextView statusText=new TextView(activity);statusText.setTextColor(android.graphics.Color.rgb(31,41,51));
        statusText.setTextSize(15);statusText.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        statusCard.addView(statusText,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView primaryNotice=new TextView(activity);primaryNotice.setTextColor(android.graphics.Color.rgb(72,80,88));
        primaryNotice.setTextSize(14);primaryNotice.setVisibility(View.GONE);
        LinearLayout.LayoutParams noticeParams=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        noticeParams.topMargin=dp(6);statusCard.addView(primaryNotice,noticeParams);
        TextView deliveryText=new TextView(activity);deliveryText.setTextColor(android.graphics.Color.rgb(54,64,74));
        deliveryText.setTextSize(14);deliveryText.setVisibility(View.GONE);
        LinearLayout.LayoutParams deliveryParams=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        deliveryParams.topMargin=dp(8);statusCard.addView(deliveryText,deliveryParams);
        TextView repairUnavailableText=new TextView(activity);
        repairUnavailableText.setText(R.string.flow_delivery_repair_unavailable);
        repairUnavailableText.setTextColor(android.graphics.Color.rgb(91,100,109));
        repairUnavailableText.setTextSize(13);repairUnavailableText.setVisibility(View.GONE);
        LinearLayout.LayoutParams repairUnavailableParams=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        repairUnavailableParams.topMargin=dp(6);statusCard.addView(repairUnavailableText,repairUnavailableParams);
        body.addView(statusCard,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView resultText=new TextView(activity);resultText.setTextIsSelectable(true);
        resultText.setMaxLines(8);resultText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Button resultExpand=button(activity.getString(R.string.flow_expand_full_result));
        resultExpand.setVisibility(View.GONE);
        LinearLayout resultCard=paperCard();resultCard.addView(resultText,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        addAction(resultCard,resultExpand);
        body.addView(resultCard);
        TextView content=new TextView(activity);
        LinearLayout detailCard=paperCard();detailCard.addView(content,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        detailCard.setVisibility(View.GONE);
        Button toggleDetail=button(activity.getString(R.string.flow_expand_task_details));
        toggleDetail.setOnClickListener(v->{boolean show=detailCard.getVisibility()!=View.VISIBLE;
            detailCard.setVisibility(show?View.VISIBLE:View.GONE);
            toggleDetail.setText(activity.getString(show?R.string.flow_collapse_task_details:R.string.flow_expand_task_details));});
        content.setTextIsSelectable(true);
        LinearLayout resultActions=new LinearLayout(activity);resultActions.setOrientation(LinearLayout.VERTICAL);
        Button openSource=button(activity.getString(R.string.flow_return_to_note));
        if(initial.noteId.isEmpty()||sourceNoteOpener==null)openSource.setVisibility(View.GONE);
        addAction(resultActions,openSource);
        Button continueWriting=button(activity.getString(R.string.flow_return_to_note));
        if(initial.noteId.isEmpty()||sourceNoteOpener==null)continueWriting.setVisibility(View.VISIBLE);
        else continueWriting.setVisibility(View.GONE);
        addAction(resultActions,continueWriting);
        Button appendResult=primaryButton(activity.getString(R.string.flow_append_result_to_note));
        appendResult.setVisibility(View.GONE);addAction(resultActions,appendResult);
        LinearLayout artifacts=new LinearLayout(activity);artifacts.setOrientation(LinearLayout.VERTICAL);
        artifacts.setVisibility(View.GONE);
        body.addView(artifacts);
        body.addView(resultActions);
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.VERTICAL);
        Button openTaskList=button(activity.getString(R.string.flow_open_task_list));addAction(actions,openTaskList);
        Button refresh=button(activity.getString(R.string.flow_refresh)),retry=button(activity.getString(R.string.flow_retry_submit)),stop=button(activity.getString(R.string.flow_stop)),once=button(activity.getString(R.string.flow_closed_approval)),deny=button(activity.getString(R.string.flow_deny));
        addAction(actions,refresh);addAction(actions,retry);addAction(actions,stop);addAction(actions,once);addAction(actions,deny);body.addView(actions);
        Button copyDiagnostic=button(activity.getString(R.string.flow_copy_diagnostic));
        LinearLayout technicalActions=new LinearLayout(activity);technicalActions.setOrientation(LinearLayout.VERTICAL);
        addAction(technicalActions,copyDiagnostic);
        detailCard.addView(technicalActions,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        Button continueTask=button(activity.getString(R.string.flow_continue_chat)),
                repairDelivery=button(activity.getString(R.string.flow_delivery_repair_button)),
                previous=button(activity.getString(R.string.flow_previous_round)),video=button(activity.getString(R.string.flow_video_storyboard));
        addAction(actions,continueTask);addAction(actions,repairDelivery);addAction(actions,previous);
        addAction(actions,video);
        LinearLayout history=new LinearLayout(activity);history.setOrientation(LinearLayout.VERTICAL);
        body.addView(history);
        body.addView(toggleDetail);body.addView(detailCard);
        AlertDialog dialog=ChoiceRows.builder(activity).setTitle(initial.title).setView(scroll(body))
                .setNegativeButton(activity.getString(R.string.flow_close),null).create();
        AgentTaskStore.Task[] latestTask={initial};
        boolean[] resultExpanded={false};
        continueWriting.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];
            if(t!=null&&!t.status.terminal())dialog.dismiss();});
        String[] displayInput={inputText(initial)};
        long[] observedVersion={AgentTaskSubmissionExecutor.stateVersion(clientId)};
        statusText.setText(statusCardText(initial));deliveryText.setText(deliverySummaryText(initial));
        String initialNotice=primaryNoticeText(initial);primaryNotice.setText(initialNotice);
        primaryNotice.setVisibility(initialNotice.isEmpty()?View.GONE:View.VISIBLE);
        deliveryText.setVisibility(deliverySummaryText(initial).isEmpty()?View.GONE:View.VISIBLE);
        content.setText(detail(initial,displayInput[0]));
        updateResult(resultText,resultCard,resultExpand,initial,resultExpanded[0]);
        resultExpand.setOnClickListener(v->{resultExpanded[0]=!resultExpanded[0];
            updateResult(resultText,resultCard,resultExpand,latestTask[0],resultExpanded[0]);});
        for(AgentTaskStore.Task child:tasks.children(clientId)){
            String input=inputText(child);String summary=input.length()>48?input.substring(0,48)+"…":input;
            String childLabel=activity.getString(R.string.flow_open_followup_round,status(child.status))+" · "+summary;
            String childDelivery=historyDeliveryCue(child);
            if(!childDelivery.isEmpty())childLabel+="\n"+childDelivery;
            Button open=button(childLabel);
            open.setOnClickListener(v->{dialog.dismiss();showDetail(child.clientTaskId);});history.addView(open);
        }
        history.setVisibility(history.getChildCount()==0?View.GONE:View.VISIBLE);
        AtomicBoolean polling=new AtomicBoolean();
        long[] retryDelay={2500L};
        Runnable[] update=new Runnable[1];
        update[0]=()->{
            long version=AgentTaskSubmissionExecutor.stateVersion(clientId);
            if(version!=observedVersion[0]){AgentTaskStore.Task reloaded=tasks.get(clientId);if(reloaded!=null){latestTask[0]=reloaded;content.setText(detail(reloaded,displayInput[0]));updateResult(resultText,resultCard,resultExpand,reloaded,resultExpanded[0]);observedVersion[0]=version;}}
            AgentTaskStore.Task task=latestTask[0];if(task==null||!dialog.isShowing())return;
            boolean canReturnToWriting=(task.noteId==null||task.noteId.isEmpty()||sourceNoteOpener==null)
                    &&!task.status.terminal();
            continueWriting.setVisibility(canReturnToWriting?View.VISIBLE:View.GONE);
            statusText.setText(statusCardText(task));
            String notice=primaryNoticeText(task);primaryNotice.setText(notice);
            primaryNotice.setVisibility(notice.isEmpty()?View.GONE:View.VISIBLE);
            String deliverySummary=deliverySummaryText(task);deliveryText.setText(deliverySummary);
            deliveryText.setVisibility(deliverySummary.isEmpty()?View.GONE:View.VISIBLE);
            updateResult(resultText,resultCard,resultExpand,task,resultExpanded[0]);
            appendResult.setVisibility(task.status==AgentTaskStore.Status.COMPLETED
                    &&task.output!=null&&!task.output.trim().isEmpty()&&resultAppender!=null
                    ?View.VISIBLE:View.GONE);
            resultActions.setVisibility(openSource.getVisibility()==View.VISIBLE||canReturnToWriting||
                    appendResult.getVisibility()==View.VISIBLE?View.VISIBLE:View.GONE);
            boolean inFlight=AgentTaskSubmissionExecutor.isInFlight(task.clientTaskId);
            boolean bridgeRecovery=canOfferSameTaskRecovery(task,
                    tasks.submissionAttemptState(task.clientTaskId),inFlight);
            boolean savedUnsent=canSendSavedTask(task,tasks.submissionAttemptState(task.clientTaskId),inFlight);
            retry.setVisibility(bridgeRecovery||savedUnsent?View.VISIBLE:View.GONE);
            retry.setText(bridgeRecovery?activity.getString(R.string.flow_recover_same_task):savedUnsent?
                    activity.getString(R.string.flow_send_saved_task):activity.getString(R.string.flow_retry_submit));
            refresh.setVisibility(canRefreshRemoteStatus(task)?View.VISIBLE:View.GONE);
            copyDiagnostic.setOnClickListener(v->{AgentTaskStore.Task latest=latestTask[0];if(latest!=null){
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PadNote task diagnostics",diagnostic(latest)));
                toast(activity.getString(R.string.flow_diagnostic_copied));}});
            video.setVisibility(videoBundleValid&&!task.remoteTaskId.isEmpty()?View.VISIBLE:View.GONE);
            AgentConnectionStore.Config bound=null;
            try{bound=taskConnectionSource.get(task.connectionId);}catch(Exception ignored){}
            boolean hasChild=!tasks.children(task.clientTaskId).isEmpty();
            boolean canContinue=task.transport==AgentConnectionStore.Transport.BRIDGE&&
                    task.status==AgentTaskStore.Status.COMPLETED&&task.followupAvailable&&
                    task.childClientTaskId.isEmpty()&&!hasChild&&task.matches(bound);
            continueTask.setVisibility(canContinue?View.VISIBLE:View.GONE);
            boolean canRepair=task.delivery.status==AgentDelivery.Status.MISSING&&canContinue&&!hasChild;
            repairDelivery.setVisibility(canRepair?View.VISIBLE:View.GONE);
            boolean missingDelivery=task.status==AgentTaskStore.Status.COMPLETED&&
                    task.delivery.status==AgentDelivery.Status.MISSING;
            repairUnavailableText.setVisibility(missingDelivery&&!canRepair&&!hasChild
                    ?View.VISIBLE:View.GONE);
            previous.setVisibility(task.parentClientTaskId.isEmpty()?View.GONE:View.VISIBLE);
            stop.setVisibility(!task.status.terminal()&&!task.remoteTaskId.isEmpty()?View.VISIBLE:View.GONE);
            boolean approval=task.status==AgentTaskStore.Status.WAITING_FOR_APPROVAL&&!task.approvalId.isEmpty();
            once.setVisibility(approval?View.VISIBLE:View.GONE);deny.setVisibility(approval?View.VISIBLE:View.GONE);
            artifacts.removeAllViews();
            artifacts.setVisibility(task.artifacts.isEmpty()||artifactSaveLauncher==null?View.GONE:View.VISIBLE);
            if(artifactSaveLauncher!=null)for(AgentTaskStore.Artifact artifact:task.artifacts){
                Button save=button(activity.getString(R.string.flow_save_artifact,artifact.name,size(artifact.sizeBytes)));
                save.setOnClickListener(v->artifactSaveLauncher.choose(task,artifact));artifacts.addView(save);
                if(artifactImagePlacementLauncher!=null&&task.status==AgentTaskStore.Status.COMPLETED
                        &&("image/png".equals(artifact.mediaType)||"image/jpeg".equals(artifact.mediaType))){
                    Button place=button(activity.getString(R.string.agent_image_place_on_paper,artifact.name));
                    place.setOnClickListener(v->{dialog.dismiss();artifactImagePlacementLauncher.place(task,artifact);});
                    artifacts.addView(place);
                }
            }
            if(!shouldContinueObserving(task.status,task.remoteTaskId,inFlight))return;
            if(activity.getWindow().getDecorView().getWindowVisibility()!=View.VISIBLE){
                main.postDelayed(update[0],2500L);return;
            }
            if(task.remoteTaskId.isEmpty()&&inFlight) {
                main.postDelayed(update[0],1500L);return;
            }
        if(!task.remoteTaskId.isEmpty()&&polling.compareAndSet(false,true)){
            try {
                STATUS_REFRESH_EXECUTOR.execute(()->{
                    boolean success=false;AgentTaskStore.Task fresh=null;
                    try{success=refresh(task);fresh=tasks.get(clientId);}catch(RuntimeException ignored){}
                    finally{polling.set(false);}
                    final boolean refreshSucceeded=success;final AgentTaskStore.Task refreshed=fresh;
                    retryDelay[0]=refreshSucceeded?2500L:Math.min(30000L,retryDelay[0]*2L);
                    main.post(()->{if(refreshed!=null){latestTask[0]=refreshed;content.setText(detail(refreshed,displayInput[0]));updateResult(resultText,resultCard,resultExpand,refreshed,resultExpanded[0]);}observedVersion[0]=AgentTaskSubmissionExecutor.stateVersion(clientId);main.postDelayed(update[0],retryDelay[0]);});
                });
            } catch(RejectedExecutionException rejected) {
                polling.set(false);
                if(dialog.isShowing())main.postDelayed(update[0],1500L);
            }
        }
        };
        refresh.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t==null)return;
            if(!polling.compareAndSet(false,true)){toast(activity.getString(R.string.flow_task_queue_busy));return;}
            try{STATUS_REFRESH_EXECUTOR.execute(()->{
                AgentTaskStore.Task fresh=null;
                try{refresh(t);fresh=tasks.get(clientId);}catch(RuntimeException ignored){}
                finally{polling.set(false);}
                final AgentTaskStore.Task refreshed=fresh;
                main.post(()->{if(refreshed!=null){latestTask[0]=refreshed;content.setText(detail(refreshed,displayInput[0]));updateResult(resultText,resultCard,resultExpand,refreshed,resultExpanded[0]);}
                    observedVersion[0]=AgentTaskSubmissionExecutor.stateVersion(clientId);
                    update[0].run();});
            });}catch(RejectedExecutionException rejected){polling.set(false);toast(activity.getString(R.string.flow_task_queue_busy));}
        });
        openSource.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t!=null&&sourceNoteOpener.open(t.noteId))dialog.dismiss();else toast(activity.getString(R.string.flow_no_source_note));});
        openTaskList.setOnClickListener(v->{dialog.dismiss();showList();});
        appendResult.setOnClickListener(v->{AgentTaskStore.Task task=latestTask[0];
            ResultAppender appender=resultAppender;
            if(task!=null&&appender!=null&&task.status==AgentTaskStore.Status.COMPLETED
                    &&task.output!=null&&!task.output.trim().isEmpty())appender.append(task.clientTaskId);});
        retry.setOnClickListener(v->{AgentTaskStore.Task t=latestTask[0];if(t==null)return;
            int attempt=tasks.submissionAttemptState(t.clientTaskId);
            boolean bridgeRecovery=canOfferSameTaskRecovery(t,attempt,
                    AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId));
            boolean savedUnsent=canSendSavedTask(t,attempt,
                    AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId));
            int title=bridgeRecovery?R.string.flow_recover_same_task:savedUnsent?
                    R.string.flow_send_saved_task:R.string.flow_retry_submit;
            int message=bridgeRecovery?R.string.flow_recover_same_task_warning:savedUnsent?
                    R.string.flow_send_saved_task_warning:R.string.flow_retry_same_id_warning;
            ChoiceRows.builder(activity).setTitle(activity.getString(title))
                    .setMessage(activity.getString(message))
                    .setNegativeButton(activity.getString(R.string.flow_cancel),null)
                    .setPositiveButton(activity.getString(bridgeRecovery?
                                    R.string.flow_confirm_recover_same_task:savedUnsent?
                                            R.string.flow_send_saved_task_confirm:R.string.flow_retry_submit),(d,w)->{
                        AgentTaskStore.Task current=tasks.get(t.clientTaskId);
                        int latestAttempt=current==null?-1:tasks.submissionAttemptState(current.clientTaskId);
                        boolean stillEligible=AgentTaskSubmissionExecutor.sameSubmissionSnapshot(t,current)&&
                                (bridgeRecovery?canOfferSameTaskRecovery(current,latestAttempt,
                                        AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId)):
                                        canSendSavedTask(current,latestAttempt,
                                                AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId)));
                        AgentConnectionStore.Config latestConnection=null;
                        try{if(current!=null)latestConnection=taskConnectionSource.get(current.connectionId);}
                        catch(RuntimeException ignored){}
                        if(!stillEligible||!current.matches(latestConnection)){
                            if(current!=null){
                                latestTask[0]=current;
                                statusText.setText(statusCardText(current));
                                String notice=primaryNoticeText(current);primaryNotice.setText(notice);
                                primaryNotice.setVisibility(notice.isEmpty()?View.GONE:View.VISIBLE);
                                content.setText(detail(current,displayInput[0]));
                                updateResult(resultText,resultCard,resultExpand,current,resultExpanded[0]);
                            }
                            toast(activity.getString(R.string.flow_task_changed_during_submit));
                            update[0].run();
                            return;
                        }
                        if(!AgentTaskSubmissionExecutor.recoverSameTask(current,bridgeRecovery,tasks,
                                taskConnectionSource,taskClientFactory,activity.getApplicationContext()))
                            toast(activity.getString(R.string.flow_task_queue_busy));
                        update[0].run();
                    }).show();});
        continueTask.setOnClickListener(v->askFollowup(clientId,dialog,false));
        repairDelivery.setOnClickListener(v->askFollowup(clientId,dialog,true));
        previous.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null&&!t.parentClientTaskId.isEmpty()){dialog.dismiss();showDetail(t.parentClientTaskId);}});
        video.setOnClickListener(v->{AgentTaskStore.Task t=tasks.get(clientId);if(t!=null)videoDialogFactory.create(activity,connections,tasks,worker).show(t);});
        stop.setOnClickListener(v->control(clientId,"stop",dialog));once.setOnClickListener(v->control(clientId,"once",dialog));deny.setOnClickListener(v->control(clientId,"deny",dialog));
        dialog.setOnDismissListener(d->{main.removeCallbacks(update[0]);observeLifecycleForTest("dismissed");});
        dialog.show();observeLifecycleForTest("shown;isShowing="+dialog.isShowing());update[0].run();return dialog;
    }

    private void observeLifecycleForTest(String state){
        java.util.function.Consumer<String> observer=lifecycleObserverForTest;
        if(observer!=null)observer.accept(state);
    }

    void setArtifactImagePlacementLauncher(ArtifactImagePlacementLauncher launcher){
        artifactImagePlacementLauncher=launcher;
    }

    static final class DeliverySelector {
        private final Activity activity;
        private final LinearLayout card;
        private final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
        private final java.util.Map<String,TextView> chips = new java.util.LinkedHashMap<>();
        private final TextView hint;
        private boolean userTouched;

        DeliverySelector(Activity activity,List<String> initial, boolean serverContract) {
            this.activity=activity;
            this.card=new LinearLayout(activity);
            selected.addAll(AgentDelivery.normalize(initial));
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14),dp(12),dp(14),dp(12));
            card.setBackground(deliveryCardBackground());
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.topMargin=dp(8);cardParams.bottomMargin=dp(8);card.setLayoutParams(cardParams);
            TextView title=new TextView(activity);title.setText(R.string.flow_delivery_heading);
            title.setTextColor(android.graphics.Color.rgb(31,41,51));
            title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            title.setTextSize(15);card.addView(title);
            TextView textDefault=deliveryChip(activity.getString(R.string.flow_delivery_text_default),true);
            textDefault.setClickable(false);textDefault.setFocusable(false);card.addView(textDefault);
            hint=new TextView(activity);
            setServerContract(serverContract);
            hint.setTextColor(android.graphics.Color.rgb(91,100,109));hint.setTextSize(12);
            LinearLayout.LayoutParams hintParams=new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            hintParams.topMargin=dp(4);hintParams.bottomMargin=dp(6);card.addView(hint,hintParams);
            addChipRows();
        }

        View view(){return card;}
        List<String> selected(){return AgentDelivery.normalize(selected);}
        void setSelected(List<String> values){
            selected.clear();selected.addAll(AgentDelivery.normalize(values));
            for(String kind:chips.keySet())updateChip(kind);
        }
        void setPresetDefaultIfUntouched(List<String> values){
            if(userTouched)return;
            setSelected(values);
        }
        boolean userTouchedSelection(){return userTouched;}
        void setServerContract(boolean supported){
            hint.setText(supported?R.string.flow_delivery_selector_hint:R.string.flow_delivery_legacy_hint);
        }
        String selectedLabelList(){
            List<String> labels=new ArrayList<>();
            for(String kind:selected())labels.add(deliveryKindLabel(kind));
            return android.text.TextUtils.join(" · ",labels);
        }

        private void addChipRows(){
            String[][] rows={{"image","pdf"},{"video","audio"},{"file"}};
            for(String[] rowKinds:rows){
                LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
                for(String kind:rowKinds){
                    TextView chip=deliveryChip(deliveryKindLabel(kind),selected.contains(kind));
                    chip.setFocusable(true);chip.setClickable(true);
                    chip.setTag("delivery-kind-"+kind);
                    chip.setOnClickListener(v->{
                        userTouched=true;
                        if(selected.contains(kind))selected.remove(kind);else selected.add(kind);
                        updateChip(kind);
                    });
                    chips.put(kind,chip);
                    updateChip(kind);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,1f);
                    lp.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(chip,lp);
                }
                if(rowKinds.length==1){View spacer=new View(activity);
                    row.addView(spacer,new LinearLayout.LayoutParams(0,dp(42),1f));}
                card.addView(row);
            }
        }

        private void updateChip(String kind){
            TextView chip=chips.get(kind);if(chip==null)return;
            boolean checked=selected.contains(kind);
            chip.setBackground(deliveryChipBackground(checked));
            chip.setSelected(checked);
            chip.setText((checked?"✓ ":"")+deliveryKindLabel(kind));
            chip.setContentDescription(activity.getString(checked
                    ?R.string.flow_delivery_selected:R.string.flow_delivery_not_selected,
                    deliveryKindLabel(kind)));
        }

        private TextView deliveryChip(String label,boolean checked){
            TextView text=new TextView(activity);text.setText((checked?"✓ ":"")+label);
            text.setGravity(android.view.Gravity.CENTER);text.setTextSize(13);
            text.setPadding(dp(8),dp(10),dp(8),dp(10));text.setTextColor(checked
                    ?PadNoteUi.ACCENT:android.graphics.Color.rgb(43,51,59));
            text.setBackground(deliveryChipBackground(checked));return text;
        }

        private android.graphics.drawable.GradientDrawable deliveryChipBackground(boolean checked){
            android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
            bg.setColor(checked?android.graphics.Color.rgb(232,241,252):android.graphics.Color.rgb(249,248,244));
            bg.setCornerRadius(dp(18));bg.setStroke(dp(1),checked?PadNoteUi.ACCENT:android.graphics.Color.rgb(218,216,209));
            return bg;
        }

        private android.graphics.drawable.GradientDrawable deliveryCardBackground(){
            android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
            bg.setColor(android.graphics.Color.rgb(255,254,250));bg.setCornerRadius(dp(14));
            bg.setStroke(dp(1),android.graphics.Color.rgb(224,220,211));return bg;
        }

        private String deliveryKindLabel(String kind){
            switch(kind){
                case "image":return activity.getString(R.string.flow_delivery_kind_image);
                case "pdf":return activity.getString(R.string.flow_delivery_kind_pdf);
                case "video":return activity.getString(R.string.flow_delivery_kind_video);
                case "audio":return activity.getString(R.string.flow_delivery_kind_audio);
                case "file":return activity.getString(R.string.flow_delivery_kind_file);
                default:return kind;
            }
        }

        private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    }

    private String deliveryKindLabel(String kind){
        switch(kind){
            case "image":return activity.getString(R.string.flow_delivery_kind_image);
            case "pdf":return activity.getString(R.string.flow_delivery_kind_pdf);
            case "video":return activity.getString(R.string.flow_delivery_kind_video);
            case "audio":return activity.getString(R.string.flow_delivery_kind_audio);
            case "file":return activity.getString(R.string.flow_delivery_kind_file);
            default:return kind;
        }
    }

    private String deliveryTypeList(List<String> kinds){
        List<String> labels=new ArrayList<>();for(String kind:AgentDelivery.normalize(kinds))labels.add(deliveryKindLabel(kind));
        return android.text.TextUtils.join(" · ",labels);
    }

    private LinearLayout paperCard(){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(android.graphics.Color.rgb(255,254,250));background.setCornerRadius(dp(14));
        background.setStroke(dp(1),android.graphics.Color.rgb(224,220,211));card.setBackground(background);
        card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setElevation(dp(1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.bottomMargin=dp(12);card.setLayoutParams(lp);return card;
    }

    private void updateResult(TextView resultText,View resultCard,Button resultExpand,
                              AgentTaskStore.Task task,boolean expanded){
        String output=task.output==null?"":task.output;
        boolean hasOutput=!output.trim().isEmpty();
        resultCard.setVisibility(hasOutput?View.VISIBLE:View.GONE);
        resultText.setText(hasOutput?activity.getString(R.string.flow_result_label)+"\n"+output:"");
        resultText.setMaxLines(expanded?Integer.MAX_VALUE:8);
        resultText.setEllipsize(expanded?null:android.text.TextUtils.TruncateAt.END);
        resultExpand.setText(activity.getString(expanded?R.string.flow_collapse_full_result:
                R.string.flow_expand_full_result));
        resultExpand.setVisibility(hasOutput?View.VISIBLE:View.GONE);
    }

    private String primaryNoticeText(AgentTaskStore.Task task) {
        if(task==null)return "";
        if(task.status==AgentTaskStore.Status.WAITING_FOR_APPROVAL&&
                (!task.approvalTitle.isEmpty()||!task.approvalDescription.isEmpty())) {
            StringBuilder text=new StringBuilder(activity.getString(R.string.flow_pending_approval_label));
            if(!task.approvalTitle.isEmpty())text.append(" ").append(task.approvalTitle);
            if(!task.approvalDescription.isEmpty())text.append("\n").append(task.approvalDescription);
            return text.toString();
        }
        if((task.status==AgentTaskStore.Status.FAILED||task.status==AgentTaskStore.Status.CANCELLED||
                task.status==AgentTaskStore.Status.INTERRUPTED)&&!task.error.trim().isEmpty())
            return activity.getString(R.string.flow_failure_reason_label)+" "+task.error;
        return "";
    }

    private String statusCardText(AgentTaskStore.Task task){AgentTaskSubmissionExecutor.ProgressInfo progress=AgentTaskSubmissionExecutor.progressInfo(task.clientTaskId);long updated=Math.max(task.updatedAt,progress==null?tasks.submissionAttemptTime(task.clientTaskId):progress.updatedAt);long age=Math.max(0,(System.currentTimeMillis()-updated)/1000L);return taskStatus(task)+"\n"+
            task.connectionName+"\n"+
            activity.getString(R.string.flow_stage_updated_ago,age);}

    private String deliverySummaryText(AgentTaskStore.Task task) {
        if (task == null || task.status != AgentTaskStore.Status.COMPLETED ||
                task.delivery == null || task.expectedOutputs.isEmpty()) return "";
        AgentDelivery.Summary summary=task.delivery;
        if(summary.status==AgentDelivery.Status.PENDING||summary.status==AgentDelivery.Status.NOT_REQUESTED)
            return "";
        String provenance=summary.reportedByServer
                ?activity.getString(R.string.flow_delivery_server_checked)
                :activity.getString(R.string.flow_delivery_local_checked);
        if(summary.status==AgentDelivery.Status.DELIVERED)
            return activity.getString(R.string.flow_delivery_delivered,
                    deliveryTypeList(summary.expected))+"\n"+provenance;
        List<String> present=new ArrayList<>(summary.expected);present.removeAll(summary.missing);
        return activity.getString(R.string.flow_delivery_missing,
                deliveryTypeList(summary.missing),present.isEmpty()?activity.getString(R.string.flow_delivery_none):
                        deliveryTypeList(present))+"\n"+provenance;
    }

    private boolean refresh(AgentTaskStore.Task task) {
        try{AgentConnectionStore.Config c=taskConnectionSource.get(task.connectionId);if(!task.matches(c)){
            if(task.status.terminal()){
                main.post(()->toast(activity.getString(R.string.flow_task_refresh_failed,
                        activity.getString(R.string.flow_connection_identity_changed))));
                return false;
            }
            tasks.markLocal(task.clientTaskId,AgentTaskStore.Status.INTERRUPTED,activity.getString(R.string.flow_connection_revoked));return true;}
            AgentTaskClient.RemoteStatus remote=taskClientFactory.create().status(task,c);
            AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
            if(!task.matches(latest))throw new IllegalStateException(
                    activity.getString(R.string.flow_task_refresh_changed));
            tasks.applyStatus(task.clientTaskId,task.connectionId,task.connectionRevision,
                    task.remoteTaskId,remote);
            return true;
        }catch(Exception e){
            if(task.status.terminal()){
                main.post(()->toast(activity.getString(R.string.flow_task_refresh_failed,message(e))));
            }else try{tasks.markError(task.clientTaskId,task.updatedAt,
                    activity.getString(R.string.flow_task_refresh_error,message(e)));}catch(Exception ignored){}
            return false;
        }
    }

    private void control(String id,String action,AlertDialog dialog){
        AgentTaskStore.Task task=tasks.get(id);if(task==null)return;worker.execute(()->{try{
            AgentConnectionStore.Config c=taskConnectionSource.get(task.connectionId);if(!task.matches(c))throw new IllegalStateException(activity.getString(R.string.flow_connection_identity_changed));
            AgentTaskClient client=taskClientFactory.create();
            AgentTaskClient.RemoteStatus remote="stop".equals(action)
                    ?client.stop(task,c):client.approve(task,c,action);
            AgentConnectionStore.Config latest=taskConnectionSource.get(task.connectionId);
            if(!task.matches(latest))throw new IllegalStateException(activity.getString(R.string.flow_task_operation_changed));
            tasks.applyControlStatus(task,remote);
        }catch(Exception e){try{tasks.markError(id,task.updatedAt,
                activity.getString(R.string.flow_task_operation_error,message(e)));}catch(Exception ignored){}}
            main.post(()->{dialog.dismiss();showDetail(id);});});
    }

    private String detail(AgentTaskStore.Task t,String input){
        StringBuilder s=new StringBuilder();
        if(!t.parentClientTaskId.isEmpty())s.append(activity.getString(R.string.flow_followup_from_label,round(t)-1));
        if(t.transport==AgentConnectionStore.Transport.BRIDGE){
            if(s.length()>0)s.append("\n");
            s.append(activity.getString(R.string.flow_continue_status_label)).append(": ")
                    .append(t.followupAvailable?activity.getString(R.string.flow_computer_can_continue):
                            (t.followupReason.isEmpty()?activity.getString(R.string.flow_not_available):t.followupReason));
        }
        if(!input.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_round_note)).append("\n").append(input);
        if(!t.error.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_tip_label)).append(" ").append(t.error);
        if(!t.artifacts.isEmpty())s.append("\n\n").append(activity.getString(R.string.flow_artifacts_count,t.artifacts.size()));
        return s.toString().trim();
    }
    static boolean computerHasReceipt(AgentTaskStore.Status status,String remoteTaskId){return status==AgentTaskStore.Status.SUBMITTING&&remoteTaskId!=null&&!remoteTaskId.isEmpty();}
    static boolean canRefreshRemoteStatus(AgentTaskStore.Task task){
        return task!=null&&task.remoteTaskId!=null&&!task.remoteTaskId.isEmpty()&&
                (!task.status.terminal()||task.transport==AgentConnectionStore.Transport.BRIDGE&&
                        task.status==AgentTaskStore.Status.COMPLETED);
    }
    static boolean shouldContinueObserving(AgentTaskStore.Status status,String remoteTaskId,boolean inFlight){
        return status!=null&&!status.terminal()&&((remoteTaskId!=null&&!remoteTaskId.isEmpty())||inFlight);
    }
    static boolean canOfferSameTaskRecovery(AgentTaskStore.Task task,int attemptState,boolean inFlight){
        return task!=null&&task.transport==AgentConnectionStore.Transport.BRIDGE&&
                task.status==AgentTaskStore.Status.SUBMITTING&&task.remoteTaskId.isEmpty()&&
                attemptState!=0&&!inFlight;
    }
    static boolean canSendSavedTask(AgentTaskStore.Task task,int attemptState,boolean inFlight){
        return task!=null&&task.status==AgentTaskStore.Status.SUBMITTING&&task.remoteTaskId.isEmpty()&&
                attemptState==0&&!inFlight;
    }
    private String taskStatus(AgentTaskStore.Task t){if(computerHasReceipt(t.status,t.remoteTaskId))return activity.getString(R.string.flow_status_computer_received);if(t.status==AgentTaskStore.Status.SUBMITTING){AgentTaskSubmissionExecutor.ProgressInfo p=AgentTaskSubmissionExecutor.progressInfo(t.clientTaskId);AgentTaskSubmissionExecutor.Phase live=AgentTaskSubmissionExecutor.visibleProgressPhase(AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId),p==null?null:p.phase);if(live!=null)switch(live){case QUEUED:return activity.getString(R.string.flow_submission_queued);case CONNECTING:return activity.getString(R.string.flow_connecting_computer);case UPLOADING:return activity.getString(R.string.flow_upload_bytes,p.bytes());case WAITING_FOR_CONFIRMATION:return activity.getString(R.string.flow_waiting_computer_confirmation);default:break;}int attempt=tasks.submissionAttemptState(t.clientTaskId);if(attempt==0)return activity.getString(R.string.flow_saved_not_sent);String unknown=activity.getString(R.string.flow_submission_result_unknown);if(t.transport!=AgentConnectionStore.Transport.BRIDGE)return unknown+"\n"+activity.getString(R.string.flow_direct_unknown_check_computer);return unknown;}return status(t.status);}
    private String diagnostic(AgentTaskStore.Task t){
        long bytes=base64PayloadBytes(t);
        String stage=taskStatus(t);
        String error=t.error.replaceAll("(?i)(bearer\\s+)[^\\s,;]+","$1[redacted]")
                .replaceAll("(?i)(token|authorization|credential)([=: ]+)[^\\s,;]+","$1$2[redacted]")
                .replaceAll("https?://[^\\s]+","[url]")
                .replaceAll("(?i)(?:[A-Z]:\\\\|/(?:Users|home|private)/)[^\\s]+","[path]")
                .replaceAll("[\\r\\n]"," ");
        if(error.length()>240)error=error.substring(0,240);
        String instance=t.instanceId.length()>8?t.instanceId.substring(0,8):t.instanceId;
        AgentTaskSubmissionExecutor.ProgressInfo transfer=AgentTaskSubmissionExecutor.progressInfo(t.clientTaskId);
        String sent=transfer==null?"":transfer.bytes();
        long progressAt=Math.max(transfer==null?tasks.submissionAttemptTime(t.clientTaskId):transfer.updatedAt,t.updatedAt);
        int attemptState=tasks.submissionAttemptState(t.clientTaskId);
        String attempt=!t.remoteTaskId.isEmpty()?"accepted":(attemptState==1?"yes; receipt pending":(attemptState==0?"no":"unknown"));
        if(AgentTaskSubmissionExecutor.isInFlight(t.clientTaskId))attempt+=AgentTaskSubmissionExecutor.isActive(t.clientTaskId)?"; active":"; queued";
        return "clientTaskId="+t.clientTaskId+"\nremoteTaskId="+t.remoteTaskId+"\nstage="+stage+"\nstageUpdatedAt="+(progressAt>0?progressAt:t.updatedAt)+
                "\npackageBytes="+bytes+"\nsubmissionAttempt="+attempt+
                "\nuploadBytes="+sent+
                "\nconnectionRevision="+t.connectionRevision+"\nnoteRevision="+t.noteRevision+
                "\nconnectionId="+t.connectionId+"\nbridgeId="+t.bridgeId+"\ninstanceId="+t.instanceId+
                "\nhost="+AgentTaskDestinationLabel.diagnosticHost(t)+
                "\nbundleSha256="+bundleSha(t)+
                "\ntarget="+t.connectionName+"\ninstanceShort="+instance+"\nerror="+error;
    }
    private long base64PayloadBytes(AgentTaskStore.Task task){int start=jsonStringStart(task.submissionJson,"bundle_base64");if(start<0)return 0;int end=task.submissionJson.indexOf('"',start);if(end<start)return 0;long encoded=end-start;int padding=0;if(end>start&&task.submissionJson.charAt(end-1)=='=')padding++;if(end-start>1&&task.submissionJson.charAt(end-2)=='=')padding++;return encoded*3/4-padding;}
    private String bundleSha(AgentTaskStore.Task task){int start=jsonStringStart(task.submissionJson,"bundle_sha256");if(start<0)return "";int end=task.submissionJson.indexOf('"',start);return end<start?"":task.submissionJson.substring(start,end);}
    private int jsonStringStart(String json,String key){String marker="\""+key+"\"";int field=json.indexOf(marker);if(field<0)return -1;int colon=json.indexOf(':',field+marker.length());if(colon<0)return -1;int quote=json.indexOf('"',colon+1);return quote<0?-1:quote+1;}
    private String inputText(AgentTaskStore.Task t){try{return new org.json.JSONObject(t.submissionJson).optString("input","");}catch(Exception ignored){return "";}}
    private int round(AgentTaskStore.Task t){int result=1;while(!t.parentClientTaskId.isEmpty()){result++;AgentTaskStore.Task p=tasks.get(t.parentClientTaskId);if(p==null)break;t=p;}return result;}
    private static String size(long bytes){if(bytes>=1024L*1024L)return String.format(java.util.Locale.getDefault(),"%.1f MiB",bytes/(1024f*1024f));if(bytes>=1024L)return String.format(java.util.Locale.getDefault(),"%.1f KiB",bytes/1024f);return bytes+" B";}
    private String status(AgentTaskStore.Status s){switch(s){case SUBMITTING:return activity.getString(R.string.flow_status_submitting);case RUNNING:return activity.getString(R.string.flow_status_running);case WAITING_FOR_APPROVAL:return activity.getString(R.string.flow_status_waiting);case STOPPING:return activity.getString(R.string.flow_status_stopping);case COMPLETED:return activity.getString(R.string.flow_status_completed);case FAILED:return activity.getString(R.string.flow_status_failed);case CANCELLED:return activity.getString(R.string.flow_status_cancelled);default:return activity.getString(R.string.flow_status_interrupted);}}
    private Button button(String text){Button b=new Button(activity);b.setText(text);return b;}
    private Button primaryButton(String text){
        Button button=new Button(activity);button.setText(text);button.setAllCaps(false);
        button.setTextColor(android.graphics.Color.WHITE);button.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        button.setTextSize(14);button.setMinHeight(dp(48));button.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(PadNoteUi.ACCENT);background.setCornerRadius(dp(19));button.setBackground(background);
        return button;
    }
    private void addAction(LinearLayout parent,Button button){parent.addView(button,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));}
    private ScrollView scroll(View v){ScrollView s=new ScrollView(activity);s.addView(v);return s;}
    private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private void toast(String m){Toast.makeText(activity,m,Toast.LENGTH_LONG).show();}
    private String message(Throwable e){return e.getMessage()==null?activity.getString(R.string.flow_unknown_error):e.getMessage();}
}
