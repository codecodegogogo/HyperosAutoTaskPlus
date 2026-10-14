package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miui.autotask.taskitem.TaskItem;
import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Configuration and scheduling-state regression only: no hardware, alarms or files. */
public final class ThenExecutionTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String FIRST_KEY = "key_test_volume_result_item";
    private static final String SECOND_KEY = "key_test_notification_result_item";
    private static int checks;

    public static void main(String[] args) throws Exception {
        actionValidation();
        delays();
        roundTrips();
        deepCopies();
        invalidPlans();
        codec();
        editorDrafts();
        chainReferences();
        executionState();
        restartDisposition();
        restorationKinds();
        System.out.println("Then execution checks passed: " + checks);
    }

    private static void actionValidation() {
        ThenAction action = action(FIRST_KEY, "  line one\n\u7b2c\u4e8c\u884c \ud83c\udf1f  ");
        check(action.isValid() && FIRST_KEY.equals(action.getKey()), "native result key is preserved");
        check(action.copy() != action && action.getJson().equals(action.copy().getJson()), "action snapshots are independent");
        for (String key : new String[]{null, "", " ", "_result_item", "key_test_condition_item",
                " key_test_result_item", FirstAppKeys.KEY_THEN_EXECUTION_RESULT}) {
            reject(() -> new ThenAction(key, "{}"), "invalid/condition/nested result key is rejected");
            JsonObject bad = GSON.toJsonTree(action).getAsJsonObject();
            bad.addProperty("key", key);
            ThenAction restored = GSON.fromJson(bad, ThenAction.class);
            check(!restored.isValid() && !restored.copy().isValid(), "copy must not repair a rejected action key");
        }
        for (String json : new String[]{null, "", " \n\r\t ", "null", " null "}) {
            reject(() -> new ThenAction(FIRST_KEY, json), "missing action payload is rejected");
            JsonObject bad = GSON.toJsonTree(action).getAsJsonObject();
            bad.addProperty("json", json);
            ThenAction restored = GSON.fromJson(bad, ThenAction.class);
            check(!restored.isValid() && !restored.copy().isValid(), "copy must preserve an invalid payload");
        }
        ThenAction missing = GSON.fromJson("{}", ThenAction.class);
        check(!missing.isValid() && missing.getKey().isEmpty() && missing.getJson().isEmpty(), "missing action fields stay invalid");
    }

    private static void delays() {
        ThenGroup group = new ThenGroup();
        ThenGroup another = new ThenGroup();
        check(UUID.fromString(group.getId()).toString().equals(group.getId()), "new groups have a stable UUID");
        check(!group.getId().equals(another.getId()), "added groups have independent identities");
        check(group.getDelayAmount() == 1 && group.getDelayUnit() == ThenGroup.UNIT_SECONDS
                && group.delayMillis() == 1000L, "default delay is one second");
        check(group.getActions().isEmpty() && !group.isValid(), "an empty group is an editable but invalid draft");
        group.setActions(Collections.singletonList(action(FIRST_KEY, "first")));
        for (int unit : new int[]{ThenGroup.UNIT_SECONDS, ThenGroup.UNIT_MINUTES}) {
            for (int amount : new int[]{1, 2, 60, 99_999, ThenGroup.MAX_DELAY_AMOUNT}) {
                group.setDelay(amount, unit);
                long expected = (long) amount * (unit == ThenGroup.UNIT_MINUTES ? 60_000L : 1_000L);
                check(group.isValid() && group.delayMillis() == expected, "seconds/minutes use long arithmetic at every boundary");
                ThenGroup restored = GSON.fromJson(GSON.toJson(group), ThenGroup.class);
                check(restored.delayMillis() == expected && restored.getDelayUnit() == unit, "delay amount and unit survive Gson");
            }
        }
        check(group.delayMillis() == 59_999_999_940_000L, "nine-digit minutes never overflow into immediate execution");
        for (int amount : new int[]{Integer.MIN_VALUE, -1, 0, 1_000_000_000, Integer.MAX_VALUE}) {
            String before = GSON.toJson(group);
            reject(() -> group.setDelay(amount, ThenGroup.UNIT_SECONDS), "invalid delay amount is rejected");
            check(before.equals(GSON.toJson(group)), "invalid delay edits do not replace a saved delay");
            invalidDelay(group, "delayAmount", GSON.toJsonTree(amount));
        }
        for (int unit : new int[]{Integer.MIN_VALUE, -1, 2, Integer.MAX_VALUE}) {
            String before = GSON.toJson(group);
            reject(() -> group.setDelay(10, unit), "unknown delay unit is rejected");
            check(before.equals(GSON.toJson(group)), "invalid unit edits do not alter a saved delay");
            invalidDelay(group, "delayUnit", GSON.toJsonTree(unit));
        }
        invalidDelay(group, "delayAmount", JsonNull.INSTANCE);
        invalidDelay(group, "delayUnit", JsonNull.INSTANCE);
        for (String id : new String[]{null, "", " \t "}) {
            JsonObject data = GSON.toJsonTree(group).getAsJsonObject();
            data.addProperty("id", id);
            ThenGroup restored = GSON.fromJson(data, ThenGroup.class);
            check(!restored.isValid() && !restored.copy().isValid(), "missing group identity stays invalid after copying");
        }
    }

    private static void invalidDelay(ThenGroup original, String field, JsonElement value) {
        JsonObject data = GSON.toJsonTree(original).getAsJsonObject();
        data.add(field, value);
        ThenGroup restored = GSON.fromJson(data, ThenGroup.class);
        check(!restored.isValid() && !restored.copy().isValid(), "corrupt/null persisted delays cannot execute");
        rejectState(restored::delayMillis, "a corrupt delay cannot silently become zero milliseconds");
    }

    private static void roundTrips() throws Exception {
        ThenResultItem original = sample();
        check(original.l() && original.hasGroups() && original.isSupportedVersion(), "sample plan is complete");
        check(FirstAppKeys.KEY_THEN_EXECUTION_RESULT.equals(original.e()), "container key is fixed");
        check(ThenRuntime.itemClass(original.e()) == ThenResultItem.class
                && ThenRuntime.newItem(original.e()) instanceof ThenResultItem, "runtime factory accepts the persisted container key");
        check(ThenRuntime.itemClass(FIRST_KEY) == null && ThenRuntime.newItem(null) == null, "runtime factory leaves unrelated result items alone");
        check(new ThenResultItem(original.e()).e().equals(original.e()), "factory key constructor accepts the container");
        reject(() -> new ThenResultItem(FIRST_KEY), "container constructor rejects native result keys");
        reject(() -> new ThenResultItem(null), "container constructor rejects null");
        for (ThenResultItem restored : Arrays.asList(
                GSON.fromJson(GSON.toJson(original), ThenResultItem.class), serialized(original), original.copy())) {
            check(restored != original && restored.l(), "restored plan is a detached valid configuration");
            check(GSON.toJson(original).equals(GSON.toJson(restored)), "Gson/Serializable/copy preserve every config and host field");
            check(restored.code == 2 && restored.getGroups().size() == 3, "host result code and group count are retained");
            List<ThenGroup> groups = restored.getGroups();
            check(groups.get(0).delayMillis() == 5000 && groups.get(1).delayMillis() == 120_000,
                    "mixed units keep the ordered delays");
            check(groups.get(0).getActions().size() == 2 && groups.get(1).getActions().size() == 1,
                    "each group retains its own ordered native actions");
            check(groups.get(0).getActions().get(0).getKey().equals(groups.get(1).getActions().get(0).getKey()),
                    "the same native action key may occur in later groups");
            check(groups.get(2).delayMillis() == 59_999_999_940_000L, "maximum delay survives all persistence paths");
        }
        ThenGroup group = original.getGroups().get(0);
        check(GSON.toJson(group).equals(GSON.toJson(serialized(group))), "a group can be passed as Serializable");
        ThenAction action = group.getActions().get(0);
        check(GSON.toJson(action).equals(GSON.toJson(serialized(action))), "an action payload can be passed as Serializable");
    }

    private static void deepCopies() {
        ThenAction action = action(FIRST_KEY, "saved");
        List<ThenAction> inputs = new ArrayList<>(Collections.singletonList(action));
        ThenGroup group = new ThenGroup();
        group.setActions(inputs);
        inputs.clear();
        check(group.getActions().size() == 1 && group.getActions().get(0) != action, "setActions takes a deep snapshot");
        List<ThenAction> exposed = group.getActions();
        exposed.clear();
        check(group.getActions().size() == 1, "getActions does not expose the saved list");
        ThenGroup groupCopy = group.copy();
        groupCopy.setDelay(9, ThenGroup.UNIT_MINUTES);
        groupCopy.setActions(Collections.singletonList(action(SECOND_KEY, "changed")));
        check(group.getDelayAmount() == 1 && FIRST_KEY.equals(group.getActions().get(0).getKey()), "group edits never leak to the source");
        check(group.getId().equals(groupCopy.getId()), "editing a group does not change its identity");

        List<ThenGroup> sources = new ArrayList<>(Collections.singletonList(group));
        ThenResultItem saved = new ThenResultItem();
        saved.code = 2;
        saved.setGroups(sources);
        String before = GSON.toJson(saved);
        sources.clear();
        group.setDelay(8, ThenGroup.UNIT_MINUTES);
        group.setActions(Collections.emptyList());
        check(before.equals(GSON.toJson(saved)), "setGroups snapshots both the groups and their actions");

        List<ThenGroup> getterDraft = saved.getGroups();
        getterDraft.get(0).setDelay(4, ThenGroup.UNIT_MINUTES);
        getterDraft.get(0).setActions(Collections.emptyList());
        getterDraft.add(new ThenGroup());
        check(before.equals(GSON.toJson(saved)), "cancelling getter-based editing leaves the original intact");

        ThenResultItem draft = saved.copy();
        List<ThenGroup> draftGroups = draft.getGroups();
        draftGroups.get(0).setDelay(7, ThenGroup.UNIT_MINUTES);
        draftGroups.get(0).setActions(Collections.singletonList(action(SECOND_KEY, "replacement")));
        draft.setGroups(draftGroups);
        draft.code = 4;
        check(before.equals(GSON.toJson(saved)), "cancelling a full copy preserves both configuration and host code");
        check(!before.equals(GSON.toJson(draft)), "draft edits remain available until commit");
        saved.setGroups(draft.getGroups());
        check(saved.getGroups().get(0).getDelayAmount() == 7 && saved.code == 2, "commit replaces only the configured groups");
    }

    private static void invalidPlans() throws Exception {
        ThenResultItem empty = new ThenResultItem();
        check(!empty.hasGroups() && !empty.l() && empty.isSupportedVersion(), "new empty plan is a supported draft, not executable");
        check(!empty.copy().l() && !serialized(empty).l(), "empty drafts survive copies and page transfers");
        empty.setGroups(null);
        check(!empty.hasGroups() && !empty.l() && empty.getGroups().isEmpty(), "a null draft list is safe to inspect but not execute");
        check(GSON.toJson(empty).equals(GSON.toJson(empty.copy())), "copy does not normalize persisted null groups");

        JsonObject good = GSON.toJsonTree(sample()).getAsJsonObject();
        for (JsonElement version : Arrays.asList(JsonNull.INSTANCE, GSON.toJsonTree(-1), GSON.toJsonTree(0), GSON.toJsonTree(2))) {
            JsonObject bad = good.deepCopy();
            bad.add("schemaVersion", version);
            assertInvalidPlan(bad, "unknown/null schema is never executable");
            bad.add("groups", new JsonArray());
            ThenResultItem restored = GSON.fromJson(bad, ThenResultItem.class);
            check(!restored.isSupportedVersion() && !restored.copy().isSupportedVersion(), "an empty unknown-version plan cannot become a current draft");
        }
        for (JsonElement groups : Arrays.asList(JsonNull.INSTANCE, new JsonArray(), GSON.toJsonTree(Arrays.asList((Object) null)))) {
            JsonObject bad = good.deepCopy();
            bad.add("groups", groups);
            assertInvalidPlan(bad, "missing/empty/null-element groups are rejected");
        }
        for (JsonElement actions : Arrays.asList(JsonNull.INSTANCE, new JsonArray(), GSON.toJsonTree(Arrays.asList((Object) null)))) {
            JsonObject bad = good.deepCopy();
            bad.getAsJsonArray("groups").get(0).getAsJsonObject().add("actions", actions);
            assertInvalidPlan(bad, "missing/empty/null-element actions are rejected without discarding other groups");
        }
        JsonObject nested = good.deepCopy();
        nested.getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonArray("actions")
                .get(0).getAsJsonObject().addProperty("key", FirstAppKeys.KEY_THEN_EXECUTION_RESULT);
        assertInvalidPlan(nested, "deserialization cannot bypass the no-nesting rule");

        ThenGroup group = sample().getGroups().get(0);
        ThenResultItem duplicate = new ThenResultItem();
        duplicate.setGroups(Arrays.asList(group, group.copy()));
        check(duplicate.hasGroups() && !duplicate.l() && !duplicate.copy().l(), "duplicate group identities remain visible but cannot execute");
        assertInvalidPlan(GSON.toJsonTree(duplicate).getAsJsonObject(), "duplicate identities remain invalid after persistence");
        check(!GSON.fromJson("{}", ThenResultItem.class).l(), "missing configuration cannot execute");
    }

    private static void assertInvalidPlan(JsonObject data, String message) throws Exception {
        ThenResultItem restored = GSON.fromJson(data, ThenResultItem.class);
        check(!restored.l() && !restored.copy().l() && !serialized(restored).l(), message);
        check(GSON.toJson(restored).equals(GSON.toJson(restored.copy())), "invalid drafts are copied without being silently repaired");
    }

    private static void codec() {
        ThenActionCodec.configure(NativeFactory.class);
        NativeResult first = new NativeResult(FIRST_KEY, "  \u539f\u751f\u64cd\u4f5c\n\ud83d\udd14  ", true);
        first.code = 2;
        ThenAction encoded = ThenActionCodec.encode(first);
        NativeResult restored = (NativeResult) ThenActionCodec.decode(encoded);
        check(restored != first && restored.code == 2 && first.text.equals(restored.text), "native factory codec preserves parameters and host code");
        check(FIRST_KEY.equals(restored.e()) && restored.l(), "decoded native action retains its checked key and completeness");
        List<TaskItem> ordered = ThenActionCodec.decodeAll(Arrays.asList(encoded, action(SECOND_KEY, "second"), encoded));
        check(ordered.size() == 3 && FIRST_KEY.equals(ordered.get(0).e()) && SECOND_KEY.equals(ordered.get(1).e())
                && FIRST_KEY.equals(ordered.get(2).e()), "decodeAll preserves order and repeated actions");
        check(ordered.get(0) != ordered.get(2), "repeated actions restore separate native instances");
        check(ThenActionCodec.decodeAll(Collections.emptyList()).isEmpty(), "the editor may decode an empty action draft");
        reject(() -> ThenActionCodec.decodeAll(null), "a missing action list is not normalized into an empty one");
        reject(() -> ThenActionCodec.decodeAll(Arrays.asList(encoded, null)), "one corrupt action rejects the whole group");
        reject(() -> ThenActionCodec.encode(null), "null native items cannot be encoded");
        reject(() -> ThenActionCodec.encode(sample()), "a valid delayed plan still cannot be nested");
        reject(() -> ThenActionCodec.decode(null), "null action cannot be decoded");

        NativeResult incomplete = new NativeResult(FIRST_KEY, "draft", false);
        incomplete.code = 4;
        reject(() -> ThenActionCodec.encode(incomplete), "native parameter validation is required before encoding");
        reject(() -> ThenActionCodec.decode(new ThenAction(FIRST_KEY, GSON.toJson(incomplete))), "native parameter validation is required after decoding");
        NativeResult draft = (NativeResult) ThenActionCodec.copyTask(incomplete);
        check(draft != incomplete && draft.code == 4 && !draft.l() && draft.text.equals(incomplete.text), "native draft copying retains incomplete configuration");
        reject(() -> ThenActionCodec.decode(new ThenAction(FIRST_KEY, GSON.toJson(new NativeResult(SECOND_KEY, "wrong key", true)))),
                "payload key must match the stored native factory key");
        for (String key : new String[]{"key_unknown_result_item", "key_abstract_result_item", "key_bad_type_result_item", "key_alias_container_result_item"}) {
            reject(() -> ThenActionCodec.encode(new NativeResult(key, "bad factory", true)), "unavailable/abstract/non-task/container factory type cannot encode");
            reject(() -> ThenActionCodec.decode(new ThenAction(key, GSON.toJson(new NativeResult(key, "bad factory", true)))),
                    "unavailable/abstract/non-task/container factory type cannot decode");
        }
        for (String payload : new String[]{"[]", "true", "42", "\"text\"", "{", "{\"configured\":}", "{}", "{" + " ".repeat(256 * 1024) + "}"}) {
            reject(() -> ThenActionCodec.decode(new ThenAction(FIRST_KEY, payload)), "non-object/malformed/incomplete/oversized JSON is rejected");
        }
        reject(() -> ThenActionCodec.configure(String.class), "an incompatible factory cannot replace the configured factory");
        check(ThenActionCodec.decode(encoded).l(), "failed reconfiguration leaves the previous factory usable");
    }

    private static void editorDrafts() {
        ThenEditorSupport.configure(NativeFactory.class);
        ThenResultItem plan = (ThenResultItem) ThenEditorSupport.newPlan();
        check(plan.code == 2 && ThenEditorSupport.validationError(plan).isEmpty(), "an empty supported plan can be removed or left unused");
        ThenGroup group = (ThenGroup) ThenEditorSupport.newGroup();
        ThenEditorSupport.setActions(group, Arrays.asList(new NativeResult(FIRST_KEY, "first", true), new NativeResult(SECOND_KEY, "second", true)));
        ThenEditorSupport.setGroups(plan, Collections.singletonList(group));
        check(plan.l() && ThenEditorSupport.validationError(plan).isEmpty(), "bridge accepts complete native actions in a group");
        check(ThenEditorSupport.decodeActions(group).size() == 2, "bridge reopens existing native action settings");
        String before = GSON.toJson(plan);
        ThenResultItem draft = (ThenResultItem) ThenEditorSupport.copyPlan(plan);
        List<ThenGroup> groups = ThenEditorSupport.groups(draft);
        groups.get(0).setDelay(4, ThenGroup.UNIT_MINUTES);
        ThenEditorSupport.setGroups(draft, groups);
        check(before.equals(GSON.toJson(plan)) && !before.equals(GSON.toJson(draft)), "cancelling the editor bridge never changes the source plan");
        String actionsBefore = GSON.toJson(group);
        reject(() -> ThenEditorSupport.setActions(group, Arrays.asList(new NativeResult(FIRST_KEY, "valid", true), new NativeResult(SECOND_KEY, "unfinished", false))),
                "an incomplete picker result cannot partially replace the saved actions");
        check(actionsBefore.equals(GSON.toJson(group)), "failed action commit is atomic");
        ThenEditorSupport.setGroups(draft, Collections.singletonList(new ThenGroup()));
        check(!ThenEditorSupport.validationError(draft).isEmpty(), "a newly added empty group blocks task saving");
        JsonObject unknown = GSON.toJsonTree(new ThenResultItem()).getAsJsonObject();
        unknown.addProperty("schemaVersion", 99);
        ThenResultItem bad = GSON.fromJson(unknown, ThenResultItem.class);
        check(!ThenEditorSupport.validationError(bad).isEmpty(), "unknown empty schemas are not treated as user-deleted groups");
        reject(() -> ThenEditorSupport.groups(bad), "unknown schemas cannot be silently extracted and discarded");
    }

    private static void chainReferences() {
        ActionChainResultItem first = new ActionChainResultItem();
        first.setChain("chain-a", "first chain");
        ActionChainResultItem second = new ActionChainResultItem();
        second.setChain("chain-b", "second chain");
        String chainKey = FirstAppKeys.KEY_ACTION_CHAIN_RESULT;
        check(ActionChainStore.usesChain(chainKey, GSON.toJson(first), "chain-a"), "direct action-chain reference is retained");
        check(!ActionChainStore.usesChain(chainKey, GSON.toJson(first), "chain-b"), "a different direct chain id is not a match");
        ThenGroup a = new ThenGroup();
        a.setActions(Collections.singletonList(new ThenAction(chainKey, GSON.toJson(first))));
        ThenGroup b = new ThenGroup();
        b.setDelay(3, ThenGroup.UNIT_MINUTES);
        b.setActions(Arrays.asList(action(FIRST_KEY, "unrelated"), new ThenAction(chainKey, GSON.toJson(second))));
        ThenResultItem plan = new ThenResultItem();
        plan.setGroups(Arrays.asList(a, b));
        String json = GSON.toJson(plan);
        check(ActionChainStore.usesChain(plan.e(), json, "chain-a"), "first delayed group protects its referenced chain");
        check(ActionChainStore.usesChain(plan.e(), json, "chain-b"), "later delayed groups also protect referenced chains");
        check(!ActionChainStore.usesChain(plan.e(), json, "missing"), "an absent chain id is not claimed by a plan");
        check(!ActionChainStore.usesChain(FIRST_KEY, GSON.toJson(first), "chain-a"), "an unrelated result key is not counted as a chain");
        check(!ActionChainStore.usesChain(plan.e(), null, "chain-a") && !ActionChainStore.usesChain(plan.e(), json, null)
                && !ActionChainStore.usesChain(plan.e(), json, ""), "missing payload/id is safe to inspect");
        JsonObject unknown = GSON.toJsonTree(plan).getAsJsonObject();
        unknown.addProperty("schemaVersion", 99);
        check(ActionChainStore.usesChain(plan.e(), unknown.toString(), "chain-b"), "even an unsupported draft conservatively retains its chain references");
    }

    private static void executionState() {
        ThenRuntime.ExecutionState<String> state = new ThenRuntime.ExecutionState<>();
        long first = state.begin(3);
        check(state.current(first) && state.nextIndex(first) == 0, "a new trigger starts at the first group");
        check(!state.take(first, 1), "out-of-order alarms cannot skip a group");
        check(state.take(first, 0) && !state.take(first, 0), "duplicate alarms consume a group exactly once");
        check(state.nextIndex(first) == 1, "consuming a group advances the expected index");
        check(state.rememberFirst(FIRST_KEY, "original-volume"), "the first meaningful restore state is captured");
        check(!state.rememberFirst(FIRST_KEY, "later-volume"), "a later group cannot overwrite the original restore state");
        check(state.rememberFirst(SECOND_KEY, "original-notification"), "another native action retains its own restore state");

        long second = state.begin(2);
        check(second != first && !state.current(first) && !state.take(first, 1), "retriggering invalidates callbacks from the previous generation");
        check(state.current(second) && state.nextIndex(second) == 0 && state.hasUndo(), "retriggering resets pending groups without forgetting restore state");
        state.finish(first);
        check(state.current(second), "an old completion cannot terminate the current generation");
        check(state.take(second, 0) && state.take(second, 1) && !state.take(second, 2), "only configured groups execute in order");
        state.finish(second);
        check(!state.current(second) && !state.take(second, 1), "finished callbacks cannot run again");
        check(state.undoInOrder().equals(Arrays.asList("original-volume", "original-notification")), "undo preserves first-execution order");
        check(state.undoReverse().equals(Arrays.asList("original-notification", "original-volume")), "restoration unwinds actions in reverse order");
        List<String> exposed = state.undoInOrder();
        exposed.clear();
        check(state.undoInOrder().size() == 2, "undo getters return a detached list snapshot");
        List<String> reversed = state.undoReverse();
        reversed.clear();
        check(state.undoReverse().size() == 2, "reverse undo getter does not expose internal state");

        ThenRuntime.ExecutionState<String> other = new ThenRuntime.ExecutionState<>();
        long otherToken = other.begin(1);
        other.rememberFirst(FIRST_KEY, "other-task-volume");
        long cancelled = state.begin(1);
        state.cancel();
        check(!state.current(cancelled) && !state.take(cancelled, 0) && state.hasUndo(), "cancelling invalidates pending callbacks but retains actions to restore");
        check(other.current(otherToken) && other.take(otherToken, 0) && other.undoInOrder().equals(Collections.singletonList("other-task-volume")),
                "cancellation and restore state remain isolated between tasks");
        check("original-volume".equals(state.removeUndo(FIRST_KEY)) && state.removeUndo(FIRST_KEY) == null, "restored native action is removed exactly once");
        check(state.undoInOrder().equals(Collections.singletonList("original-notification")), "removing one undo does not erase others");
        state.clearUndo();
        check(!state.hasUndo() && state.undoReverse().isEmpty() && other.hasUndo(), "clearing one task's undo does not touch another task");
    }

    private static void restorationKinds() {
        for (String key : new String[]{FirstAppKeys.KEY_AUDIO_RECORD_RESULT, FirstAppKeys.KEY_SCREEN_RECORD_RESULT}) {
            DeviceActionResultItem item = new DeviceActionResultItem(key);
            check(ThenRuntime.hasRestoration(item), "starting a recording keeps its task-owned stop operation");
            item.setStartRecording(false);
            check(!ThenRuntime.hasRestoration(item), "stopping a recording cannot erase a previous meaningful undo");
        }
        check(ThenRuntime.hasRestoration(new DeviceActionResultItem(FirstAppKeys.KEY_PLAY_AUDIO_RESULT)), "audio playback keeps its owned stop operation");
        for (String key : new String[]{FirstAppKeys.KEY_SCREENSHOT_RESULT, FirstAppKeys.KEY_PHOTO_RESULT, FirstAppKeys.KEY_CALL_RESULT,
                FirstAppKeys.KEY_SMS_RESULT, FirstAppKeys.KEY_EMAIL_RESULT, FirstAppKeys.KEY_NOTIFICATION_RESULT}) {
            check(!ThenRuntime.hasRestoration(new DeviceActionResultItem(key)), "one-shot results do not enter the restore collection");
        }
        check(!ThenRuntime.hasRestoration(new ActionChainResultItem()) && !ThenRuntime.hasRestoration(new ThenResultItem()),
                "action chains and delayed containers cannot be recursively restored");
        for (String key : new String[]{"key_lock_screen_result_item", "key_absorbed_result_item"}) {
            check(!ThenRuntime.hasRestoration(new NativeResult(key, "one shot", true)), "known native no-op restores do not replace meaningful undo");
        }
        check(ThenRuntime.hasRestoration(new NativeResult(FIRST_KEY, "native restore", true)), "overridden native restore operations remain reusable");
        TaskItem noRestore = new TaskItem() {
            @Override public String e() { return FIRST_KEY; }
            @Override public boolean l() { return true; }
            @Override public String h() { return "no restore"; }
            @Override public String g() { return "no restore"; }
            @Override public int b() { return 0; }
            @Override public int c() { return 0; }
            @Override public int i() { return 0; }
        };
        check(!ThenRuntime.hasRestoration(noRestore), "the TaskItem stub's inherited restore is never treated as executable undo");
    }

    private static void restartDisposition() {
        for (boolean enabled : new boolean[]{false, true}) {
            for (boolean explicitExit : new boolean[]{false, true}) {
                for (boolean timeRange : new boolean[]{false, true}) {
                    for (boolean nativeExecuted : new boolean[]{false, true}) {
                        boolean expected = enabled && (timeRange ? nativeExecuted : explicitExit);
                        check(ThenRuntime.shouldKeepRestoration(enabled, explicitExit, timeRange, nativeExecuted) == expected,
                                "restart recovery respects enabled state, time-window expiry and explicit exit settings");
                    }
                }
            }
        }

        ThenRuntime.ExecutionState<String> expired = new ThenRuntime.ExecutionState<>();
        long old = expired.begin(1);
        expired.rememberFirst(FIRST_KEY, "yesterday-baseline-50");
        if (!ThenRuntime.shouldKeepRestoration(true, false, true, false)) {
            expired.cancel();
            expired.clearUndo();
        }
        check(!expired.current(old) && !expired.hasUndo(), "an expired native time window loses its old restore snapshot");
        long today = expired.begin(1);
        check(expired.take(today, 0) && expired.rememberFirst(FIRST_KEY, "today-baseline-70"),
                "an unchanged plan in a new time window can capture a new baseline for the same native key");
        check(expired.undoReverse().equals(Collections.singletonList("today-baseline-70")),
                "the next window cannot replay yesterday's native restore state");
        check(!ThenRuntime.shouldKeepRestoration(true, true, true, false),
                "an explicit exit cannot revive an expired time window or block its next start");

        ThenRuntime.ExecutionState<String> active = new ThenRuntime.ExecutionState<>();
        active.begin(1);
        active.rememberFirst(FIRST_KEY, "live-baseline-50");
        if (!ThenRuntime.shouldKeepRestoration(true, false, true, true)) {
            active.cancel();
            active.clearUndo();
        }
        check(active.hasUndo() && !active.rememberFirst(FIRST_KEY, "late-baseline-70"),
                "a still-active native time window preserves its first restore snapshot without restarting groups");
        check(active.undoReverse().equals(Collections.singletonList("live-baseline-50")),
                "active-window recovery preserves the original restore value");
        if (!ThenRuntime.shouldKeepRestoration(false, true, true, true)) {
            active.cancel();
            active.clearUndo();
        }
        check(!active.hasUndo(), "disabled tasks cannot revive otherwise valid pending restoration");
        check(ThenRuntime.shouldKeepRestoration(true, true, false, false),
                "non-time tasks may recover explicit-exit eligibility after the host resets their state");
    }

    private static ThenResultItem sample() {
        ThenGroup first = new ThenGroup();
        first.setDelay(5, ThenGroup.UNIT_SECONDS);
        first.setActions(Arrays.asList(action(FIRST_KEY, "  \u7b2c\u4e00\u884c\n\u7b2c\u4e8c\u884c \ud83c\udf1f  "), action(SECOND_KEY, "next")));
        ThenGroup second = new ThenGroup();
        second.setDelay(2, ThenGroup.UNIT_MINUTES);
        second.setActions(Collections.singletonList(action(FIRST_KEY, "restore volume")));
        ThenGroup third = new ThenGroup();
        third.setDelay(ThenGroup.MAX_DELAY_AMOUNT, ThenGroup.UNIT_MINUTES);
        third.setActions(Collections.singletonList(action(SECOND_KEY, "last")));
        ThenResultItem result = new ThenResultItem();
        result.code = 2;
        result.setGroups(Arrays.asList(first, second, third));
        return result;
    }

    private static ThenAction action(String key, String text) {
        return new ThenAction(key, GSON.toJson(new NativeResult(key, text, true)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T serialized(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(value); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    public static final class NativeFactory {
        public static Class<?> h(String key) {
            if (FIRST_KEY.equals(key) || SECOND_KEY.equals(key)) return NativeResult.class;
            if ("key_abstract_result_item".equals(key)) return TaskItem.class;
            if ("key_bad_type_result_item".equals(key)) return String.class;
            if ("key_alias_container_result_item".equals(key)) return ThenResultItem.class;
            return null;
        }
    }

    public static final class NativeResult extends TaskItem {
        private static final long serialVersionUID = 1L;
        private String nativeKey;
        private String text;
        private boolean configured;

        public NativeResult() {}
        NativeResult(String key, String text, boolean configured) {
            nativeKey = key;
            this.text = text;
            this.configured = configured;
        }

        @Override public String e() { return nativeKey; }
        @Override public boolean l() { return configured && text != null && !text.trim().isEmpty(); }
        @Override public String h() { return "test native action"; }
        @Override public String g() { return text; }
        @Override public int b() { return 0; }
        @Override public int c() { return 0; }
        @Override public int i() { return 0; }
        @Override public void n() { throw new AssertionError("tests must never execute native actions"); }
        @Override public void o() { throw new AssertionError("tests must never restore native actions"); }
    }

    private static void reject(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }

    private static void rejectState(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
