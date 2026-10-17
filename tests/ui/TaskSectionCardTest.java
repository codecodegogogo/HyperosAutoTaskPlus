package io.github.codecodegogogo.HyperosAutoTaskPlus;

import android.app.Activity;
import android.content.Context;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Exercises the production hierarchy operations without Android or a connected phone. */
public final class TaskSectionCardTest {
    private static final String CARD_HEADER = "hyperauto.then.cardHeader";
    private static final Class<?> STATE = nested("State");
    private static final Class<?> GROUP_UI = nested("GroupUi");
    private static int checks;

    public static void main(String[] args) throws Exception {
        nativeCardsAndDelayedGroups();
        rollbackAfterPartialMount();
        unrelatedHeadersStayUnchanged();
        System.out.println("Task section card checks passed: " + checks);
    }

    private static void nativeCardsAndDelayedGroups() throws Exception {
        Fixture f = new Fixture();
        call("mountNativeSections", new Class<?>[]{STATE}, f.state);
        call("reorder", new Class<?>[]{STATE}, f.state);
        checkCard(f.conditionHeader, f.conditions);
        checkCard(f.resultHeader, f.results);
        check(cardEnabled(f.conditionHeader) && cardEnabled(f.resultHeader), "native headers enable card drawing");
        check(f.conditionHeader.parent != f.resultHeader.parent, "native sections remain separate cards");
        check(f.screen.children.equals(Arrays.asList(f.title, f.conditionHeader.parent,
                f.resultHeader.parent, f.add, f.exit, f.spacer)), "native screen order is preserved");
        check(f.results.parent.children.get(1) == f.results, "native result instance is retained");
        check(!cardEnabled(f.spacer), "blank FlexiblePreference spacer is not a card");

        Object first = delayedGroup("first");
        Object second = delayedGroup("second");
        List<Object> groups = list(f.state, "groups");
        for (Object group : new Object[]{first, second}) {
            groups.add(group);
            call("mount", new Class<?>[]{STATE, GROUP_UI}, f.state, group);
        }
        call("reorder", new Class<?>[]{STATE}, f.state);
        for (Object group : groups) {
            Group category = (Group) field(group, "section");
            check(category.children.equals(Arrays.asList(field(group, "header"), field(group, "delay"),
                    field(group, "results"))), "delayed header, delay and results share one card in order");
            check(!f.screen.children.contains(field(group, "header")), "delayed header is not a separate row");
            checkCard((Preference) field(group, "header"), (Preference) field(group, "results"));
        }
        check(f.screen.children.indexOf(field(first, "section")) == 3
                && f.screen.children.indexOf(field(second, "section")) == 4
                && f.screen.children.indexOf(f.add) == 5, "delayed cards follow native results and precede add");
        groups.remove(first);
        call("detach", new Class<?>[]{STATE, GROUP_UI}, f.state, first);
        call("reorder", new Class<?>[]{STATE}, f.state);
        check(((Group) field(first, "section")).children.isEmpty(), "deleting a group removes its header too");
        check(((Preference) field(first, "header")).parent == null, "deleted header is detached");
        check(f.screen.children.indexOf(field(second, "section")) == 3, "remaining card keeps its position");
        call("reorder", new Class<?>[]{STATE}, f.state);
        checkCard(f.resultHeader, f.results);
    }

    private static void rollbackAfterPartialMount() throws Exception {
        Fixture f = new Fixture();
        Group.rejectOnce = f.results;
        try {
            call("mountNativeSections", new Class<?>[]{STATE}, f.state);
            throw new AssertionError("injected mount failure did not fire");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException, "mount failure is propagated");
        }
        f.screen.removePreference(f.add);
        call("restoreNativeSections", new Class<?>[]{STATE}, f.state);
        for (int i = 0; i < f.original.size(); i++) f.original.get(i).setOrder(i);
        check(f.screen.children.equals(f.original), "rollback restores every original preference and its order");
        check(!cardEnabled(f.conditionHeader) && !cardEnabled(f.resultHeader), "rollback clears header overrides");
        check(list(f.state, "nativeSections").isEmpty(), "rollback clears section bookkeeping");
        call("mountNativeSections", new Class<?>[]{STATE}, f.state);
        call("reorder", new Class<?>[]{STATE}, f.state);
        checkCard(f.conditionHeader, f.conditions);
        checkCard(f.resultHeader, f.results);
    }

    private static void unrelatedHeadersStayUnchanged() throws Exception {
        Fixture f = new Fixture();
        Header unrelatedHeader = new Header();
        Content unrelatedContent = new Content("key_exit_condition_list");
        f.screen.addPreference(unrelatedHeader);
        f.screen.addPreference(unrelatedContent);
        list(f.state, "originalPreferences").addAll(Arrays.asList(unrelatedHeader, unrelatedContent));
        call("mountNativeSections", new Class<?>[]{STATE}, f.state);
        check(!cardEnabled(unrelatedHeader), "unrelated headers do not inherit card styling");
        check(unrelatedHeader.parent == f.screen && unrelatedContent.parent == f.screen,
                "only condition and result pairs are regrouped");
    }

    private static Object delayedGroup(String id) throws Exception {
        Constructor<?> constructor = GROUP_UI.getDeclaredConstructor(Object.class, String.class);
        constructor.setAccessible(true);
        Object group = constructor.newInstance(new Object(), id);
        set(group, "header", new Header());
        set(group, "section", new Group(null));
        set(group, "delay", new Preference());
        set(group, "results", new Content(id));
        return group;
    }

    private static void checkCard(Preference header, Preference content) {
        check(header.parent != null && header.parent == content.parent, "header and body have the same parent");
        check(header.parent.children.get(0) == header, "header owns the top of the shared card");
        check(header.parent.children.get(header.parent.children.size() - 1) == content,
                "body owns the bottom of the shared card");
    }

    private static boolean cardEnabled(Preference header) {
        return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(header, CARD_HEADER));
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ThenExecutionHook.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static Class<?> nested(String name) {
        try {
            return Class.forName(ThenExecutionHook.class.getName() + "$" + name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object object, String name) throws Exception {
        return (List<Object>) field(object, name);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }

    private static final class Fixture {
        final Group screen = new Group(null);
        final Preference title = new Preference();
        final Header conditionHeader = new Header();
        final Content conditions = new Content("key_condition_list");
        final Header resultHeader = new Header();
        final Content results = new Content("key_result_list");
        final Group exit = new Group(null);
        final Header spacer = new Header();
        final Preference add = new Preference();
        final List<Preference> original = Arrays.asList(title, conditionHeader, conditions,
                resultHeader, results, exit, spacer);
        final Object state;

        Fixture() throws Exception {
            for (int i = 0; i < original.size(); i++) {
                original.get(i).setOrder(i);
                screen.addPreference(original.get(i));
            }
            Constructor<?> constructor = STATE.getDeclaredConstructor(Object.class, Activity.class, Object.class,
                    Object.class, Object.class, ClassLoader.class, Method.class);
            constructor.setAccessible(true);
            state = constructor.newInstance(new Object(), null, results, screen, new Object(),
                    getClass().getClassLoader(), null);
            list(state, "originalPreferences").addAll(original);
            set(state, "add", add);
            screen.addPreference(add);
        }
    }

    public static class Preference {
        Group parent;
        int order = Integer.MAX_VALUE;
        String key;
        public Group getParent() { return parent; }
        public String getKey() { return key; }
        public void setKey(String value) { key = value; }
        public void setPersistent(boolean value) {}
        public void setOrder(int value) {
            order = value;
            if (parent != null) parent.sort();
        }
    }

    public static final class Header extends Preference {}
    public static final class Content extends Preference {
        Content(String key) { this.key = key; }
    }

    public static final class Group extends Preference {
        static Preference rejectOnce;
        final List<Preference> children = new ArrayList<>();
        public Group(Context ignored) {}
        void sort() { children.sort(Comparator.comparingInt(preference -> preference.order)); }
        public boolean addPreference(Preference child) {
            if (child == rejectOnce) {
                rejectOnce = null;
                throw new IllegalStateException("injected add failure");
            }
            if (children.contains(child)) return true;
            if (child.parent != null) throw new IllegalStateException("preference already has a parent");
            children.add(child);
            child.parent = this;
            sort();
            return true;
        }
        public boolean removePreference(Preference child) {
            if (!children.remove(child)) return false;
            child.parent = null;
            return true;
        }
    }
}
