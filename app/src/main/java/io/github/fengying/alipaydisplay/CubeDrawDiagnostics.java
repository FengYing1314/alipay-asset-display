package io.github.fengying.alipaydisplay;

import android.app.Activity;
import android.os.Looper;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 默认关闭、限定余额Ng实例的只读提交诊断；不修改绘制对象或保存业务原文。 */
final class CubeDrawDiagnostics implements AutoCloseable {
    private static final String TAG = "AlipayDisplayDiag";
    private static final String NG = "com.antfin.cube.cubebridge.view.CubeViewNgImpl";
    private static final String ACCESS = "com.antfin.cube.cubecore.accessibility.CKAccessibility";
    private static final String VIEW_TAG = "com.antfin.cube.cubecore.component.widget.CKViewTag";
    private static final String CANVAS = "com.antfin.cube.platform.context.CKCanvas";
    private static final int MAX_VIEWS = 96,
            MAX_EVENTS = 128,
            MAX_COMMANDS = 96,
            MAX_COMMAND_EVENTS = 1024;
    private static final int MAX_SEMANTIC_COMMANDS = 1536;

    private final Supplier<DisplayConfig> configuration;
    private final IdentityHashMap<View, Node> nodes = new IdentityHashMap<>();
    private Activity activity;
    private View renderRoot, cubeRoot;
    private BooleanSupplier ownerStillCurrent;
    private long session;
    private int events, commandEvents, canvasSamples;
    private int skippedNonMain;
    private boolean sampledExisting;
    private boolean sampledSemantic;
    private boolean controlledDiscovery;

    CubeDrawDiagnostics(Supplier<DisplayConfig> configuration) {
        this.configuration = configuration;
    }

    // 外部入口只接收已有归属和绘制元数据，不主动提交宿主对象。

    /** 调用方须以当前App/Page/Render和完整导航摘要实现每次重新验证的owner谓词。 */
    synchronized void bind(
            Activity currentActivity,
            View activeRenderView,
            View cubeNgRoot,
            String appId,
            long hostVersion,
            BooleanSupplier currentOwner) {
        bind(
                currentActivity,
                activeRenderView,
                cubeNgRoot,
                appId,
                hostVersion,
                null,
                null,
                currentOwner);
    }

    /** 绘制层文本发现另锁定已取证资源和路由；旧入口仅保留结构观察。 */
    synchronized void bind(
            Activity currentActivity,
            View activeRenderView,
            View cubeNgRoot,
            String appId,
            long hostVersion,
            String resourceVersion,
            String routeFingerprint,
            BooleanSupplier currentOwner) {
        try {
            if (!isMainThread()) return;
            DisplayConfig config = configuration.get();
            if (config == null
                    || !config.diagnostics
                    || !Policy.identity(appId, hostVersion)
                    || currentActivity == null
                    || activeRenderView == null
                    || cubeNgRoot == null
                    || currentOwner == null
                    || !currentOwner.getAsBoolean()
                    || !hasClassInHierarchy(cubeNgRoot, NG)
                    || !isDescendantOf(cubeNgRoot, activeRenderView)
                    || !isDescendantOf(
                            activeRenderView, currentActivity.getWindow().getDecorView())) {
                clear();
                return;
            }
            boolean same =
                    activity == currentActivity
                            && renderRoot == activeRenderView
                            && cubeRoot == cubeNgRoot
                            && ownerStillCurrent != null
                            && ownerStillCurrent.getAsBoolean();
            if (!same) {
                clear();
                activity = currentActivity;
                renderRoot = activeRenderView;
                cubeRoot = cubeNgRoot;
            }
            ownerStillCurrent = currentOwner;
            controlledDiscovery =
                    Policy.discovery(appId, hostVersion, resourceVersion, routeFingerprint);
        } catch (RuntimeException | LinkageError ignored) {
            stopAfterFailure();
        }
    }

    /** 仅接收宿主真实setAccessibility调用的元数据；不调用任何提交或setter。 */
    synchronized void onAccessibility(View view, Object accessibility) {
        try {
            if (!isMainThread()) return;
            if (!acceptsCurrentView(view)
                    || accessibility == null
                    || !ACCESS.equals(accessibility.getClass().getName())) return;
            Node node = findOrCreateNode(view);
            if (node == null) return;
            Object description = readField(accessibility, "m_Description");
            Object role = readField(accessibility, "m_Role");
            node.label = CubeTextPolicy.label(description);
            node.numeric = CubeTextPolicy.isAmount(description);
            node.labeledAmount = CubeTextPolicy.hasLabeledAmount(description);
            node.mask = CubeTextPolicy.isMaskedAmount(description);
            node.hidden = Boolean.TRUE.equals(readField(accessibility, "m_Hidden"));
            logMetadata(
                    "cube-meta session="
                            + session
                            + " view="
                            + node.ordinal
                            + " parent="
                            + resolveParentOrdinal(view)
                            + " nodeHash="
                            + node.hash
                            + " nodeSource="
                            + node.source
                            + " instanceHash="
                            + node.instanceHash
                            + " label="
                            + node.label
                            + " role="
                            + Policy.role(role)
                            + " amount="
                            + node.numeric
                            + " mask="
                            + node.mask
                            + " hidden="
                            + node.hidden
                            + " skippedNonMain="
                            + skippedNonMain);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            stopAfterFailure();
        }
    }

    /** flush阶段传null；bind/update传入已有CKCanvas，绝不读取原始提交字节。 */
    synchronized void onCanvas(String stage, View view, Object canvas) {
        try {
            if (!isMainThread()) return;
            if (!Policy.stage(stage) || !acceptsCurrentView(view)) return;
            if (canvas != null && events + 2 > MAX_EVENTS) return;
            Node node = findOrCreateNode(view);
            if (node == null) return;
            // 仅读取已证owner内组件的可访问性元数据，不调用TextView.getText或未知对象toString。
            refreshDescriptionFlags(view, node);
            String semantic = resolveSemanticLabel(view);
            Node amountMetadata = node;
            if (hasClassInHierarchy(view, "com.antfin.cube.cubecore.component.widget.CKPView")
                    && view.getParent() instanceof View parent
                    && isSupportedContainer(parent)) {
                Node ownerMetadata = findOrCreateNode(parent);
                if (ownerMetadata != null) {
                    refreshDescriptionFlags(parent, ownerMetadata);
                    amountMetadata = ownerMetadata;
                }
            }
            if (canvas == null) {
                logMetadata(
                        "cube-canvas session="
                                + session
                                + " stage="
                                + stage
                                + " view="
                                + node.ordinal
                                + " parent="
                                + resolveParentOrdinal(view)
                                + " nodeHash="
                                + node.hash
                                + " nodeSource="
                                + node.source
                                + " instanceHash="
                                + node.instanceHash
                                + " label="
                                + semantic
                                + " amount="
                                + node.numeric
                                + " mask="
                                + node.mask
                                + " canvas=false skippedNonMain="
                                + skippedNonMain);
                return;
            }
            if (!CANVAS.equals(canvas.getClass().getName())) return;
            Object raw = readField(canvas, "cmdList");
            if (!(raw instanceof ArrayList<?> commands)) return;
            boolean picture = readField(canvas, "mPicture") != null;
            boolean commandMode = Boolean.TRUE.equals(readField(canvas, "isCmd"));
            int count = commands.size(), limit = Math.min(count, MAX_COMMANDS);
            int sample = ++canvasSamples;
            logMetadata(
                    "cube-canvas session="
                            + session
                            + " sample="
                            + sample
                            + " stage="
                            + stage
                            + " view="
                            + node.ordinal
                            + " parent="
                            + resolveParentOrdinal(view)
                            + " nodeHash="
                            + node.hash
                            + " nodeSource="
                            + node.source
                            + " instanceHash="
                            + node.instanceHash
                            + " label="
                            + semantic
                            + " amount="
                            + node.numeric
                            + " mask="
                            + node.mask
                            + " canvas=true picture="
                            + picture
                            + " commandMode="
                            + commandMode
                            + " commands="
                            + Math.min(count, 1024)
                            + " truncated="
                            + (count > limit)
                            + " discovery="
                            + controlledDiscovery
                            + " skippedNonMain="
                            + skippedNonMain);
            DisplayConfig config = configuration.get();
            if (config == null || !config.diagnostics) {
                clear();
                return;
            }
            int emitted = 0;
            for (int index = 0; index < limit && commandEvents < MAX_COMMAND_EVENTS; index++) {
                Object command = commands.get(index);
                String type =
                        CubeTextPolicy.commandKind(
                                command == null ? null : command.getClass().getName());
                boolean eligible = controlledDiscovery && view.isShown();
                int lines = 0;
                boolean textRead = false;
                CubeTextPolicy.Classification classification =
                        CubeTextPolicy.classifyText(null, null);
                String layoutType = "none";
                if ("text-layout".equals(type)) {
                    Object layoutObject = readField(command, "layout");
                    if (layoutObject instanceof Layout layout) {
                        layoutType = "android-layout";
                        lines = Math.max(0, Math.min(layout.getLineCount(), 32));
                        if (eligible) {
                            CharSequence text = layout.getText();
                            String value = readBoundedText(text);
                            textRead = value != null;
                            classification =
                                    CubeTextPolicy.classifyText(
                                            value, config.amount(DisplayConfig.Kind.BALANCE));
                        }
                    } else if (layoutObject != null) layoutType = "other";
                } else if ("text-inline".equals(type) || "rich-text-inline".equals(type)) {
                    Object value = readField(command, "lines");
                    if (value instanceof Object[] lineObjects) {
                        lines = Math.min(lineObjects.length, 32);
                        layoutType = "inline-lines";
                        if (eligible && lineObjects.length <= 8) {
                            StringBuilder text = new StringBuilder();
                            boolean known = true;
                            for (Object line : lineObjects) {
                                if (line == null
                                        || !"com.antfin.cube.cubecore.draw.CRTextLineInfo"
                                                .equals(line.getClass().getName())) {
                                    known = false;
                                    break;
                                }
                                Object rawText = readField(line, "text");
                                if (!(rawText instanceof String part)
                                        || text.length() + part.length() > 256) {
                                    known = false;
                                    break;
                                }
                                text.append(part);
                            }
                            if (known) {
                                String textValue = text.toString();
                                textRead = true;
                                classification =
                                        CubeTextPolicy.classifyText(
                                                textValue,
                                                config.amount(DisplayConfig.Kind.BALANCE));
                            }
                        }
                    }
                }
                logCommandSample(
                        "cube-command session="
                                + session
                                + " sample="
                                + sample
                                + " view="
                                + node.ordinal
                                + " index="
                                + index
                                + " type="
                                + type
                                + " label="
                                + classification.label
                                + " ownerLabel="
                                + semantic
                                + " layout="
                                + layoutType
                                + " lines="
                                + lines
                                + " metadataFromParent="
                                + (amountMetadata != node)
                                + " textRead="
                                + textRead
                                + " amount="
                                + classification.pureAmount
                                + " pureAmount="
                                + classification.pureAmount
                                + " mask="
                                + classification.mask
                                + " hasNumber="
                                + classification.hasNumber
                                + " matchesConfig="
                                + classification.matchesConfig);
                emitted++;
            }
            logMetadata(
                    "cube-canvas-end session="
                            + session
                            + " sample="
                            + sample
                            + " view="
                            + node.ordinal
                            + " inspected="
                            + emitted
                            + " commandLimit="
                            + Policy.commandTruncated(count)
                            + " budgetLimit="
                            + Policy.budgetTruncated(count, emitted)
                            + " complete="
                            + Policy.complete(count, emitted));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            stopAfterFailure();
        }
    }

    /** 每个owner仅一次、有上限地检查已有组件/CKPView；不补读未知页面的历史回调。 */
    synchronized void sampleExisting() {
        try {
            if (!isMainThread()) return;
            sampleSemanticExisting();
            if (controlledDiscovery) return;
            if (sampledExisting || !acceptsCurrentView(cubeRoot)) return;
            sampledExisting = true;
            ArrayDeque<View> pending = new ArrayDeque<>();
            pending.add(cubeRoot);
            int count = 0;
            while (!pending.isEmpty() && count++ < MAX_VIEWS && events < MAX_EVENTS) {
                View view = pending.removeFirst();
                if (isSupportedContainer(view)) onCanvas("existing", view, null);
                else if (hasClassInHierarchy(
                        view, "com.antfin.cube.cubecore.component.widget.CKPView")) {
                    onCanvas("existing", view, readField(view, "canvas"));
                }
                if (view instanceof ViewGroup group) {
                    for (int index = 0;
                            index < group.getChildCount() && pending.size() < MAX_VIEWS;
                            index++) pending.addLast(group.getChildAt(index));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            stopAfterFailure();
        }
    }

    /** 每个严格余额owner仅一次；独立预算避免逐条结构日志挤掉主金额证据。 */
    synchronized void sampleSemanticExisting() {
        try {
            if (!isMainThread() || sampledSemantic || !isDiscoveryOwnerCurrent()) return;
            sampledSemantic = true;
            ArrayDeque<View> pending = new ArrayDeque<>();
            pending.add(cubeRoot);
            int visited = 0, views = 0, inspected = 0;
            boolean cancelled = false;
            while (!pending.isEmpty() && visited < 128 && views < 32) {
                if (!isDiscoveryOwnerCurrent()) {
                    cancelled = true;
                    break;
                }
                View view = pending.removeFirst();
                visited++;
                if (hasClassInHierarchy(
                        view, "com.antfin.cube.cubecore.component.widget.CKPView")) {
                    views++;
                    try {
                        Object canvas = readField(view, "canvas");
                        boolean available =
                                canvas != null && CANVAS.equals(canvas.getClass().getName());
                        Node metadata = findOrCreateNode(view);
                        int ordinal = metadata == null ? 0 : metadata.ordinal;
                        boolean shown = view.isShown(),
                                visible = view.getVisibility() == View.VISIBLE;
                        boolean windowVisible = view.getWindowVisibility() == View.VISIBLE;
                        boolean attached = view.isAttachedToWindow(),
                                alphaPositive = view.getAlpha() > 0;
                        ArrayList<?> commands =
                                available
                                                && readField(canvas, "cmdList")
                                                        instanceof ArrayList<?> list
                                        ? list
                                        : null;
                        int count = commands == null ? 0 : commands.size();
                        int limit = Math.min(count, Math.max(0, MAX_SEMANTIC_COMMANDS - inspected));
                        boolean picture = available && readField(canvas, "mPicture") != null;
                        boolean bitmap = available && readField(canvas, "bitmap") != null;
                        boolean demotion =
                                available && Boolean.TRUE.equals(readField(canvas, "demotion"));
                        boolean commandMode =
                                available && Boolean.TRUE.equals(readField(canvas, "isCmd"));
                        String[] names = new String[limit];
                        int inline = 0, layouts = 0, rich = 0, textRead = 0, examined = 0;
                        for (int index = 0; index < limit; index++) {
                            inspected++;
                            examined++;
                            Object command = commands.get(index);
                            names[index] = command == null ? null : command.getClass().getName();
                            String type = CubeTextPolicy.commandKind(names[index]);
                            if ("text-inline".equals(type)) inline++;
                            else if ("text-layout".equals(type)) layouts++;
                            else if ("rich-text-inline".equals(type)) rich++;
                            else continue;
                            if (!isDiscoveryOwnerCurrent()) {
                                cancelled = true;
                                break;
                            }
                            TextObservation observation =
                                    readTextObservation(
                                            command,
                                            type,
                                            shown && windowVisible,
                                            configuration.get());
                            if (observation.textRead) textRead++;
                            CubeTextPolicy.Classification value = observation.value;
                            logDiagnosticSafely(
                                    "cube-semantic-text session="
                                            + session
                                            + " scanView="
                                            + views
                                            + " view="
                                            + ordinal
                                            + " index="
                                            + index
                                            + " type="
                                            + type
                                            + " label="
                                            + value.label
                                            + " lines="
                                            + observation.lines
                                            + " publicRole="
                                            + observation.publicRole
                                            + " textRead="
                                            + observation.textRead
                                            + " pureAmount="
                                            + value.pureAmount
                                            + " mask="
                                            + value.mask
                                            + " hasNumber="
                                            + value.hasNumber
                                            + " matchesConfig="
                                            + value.matchesConfig);
                        }
                        logDiagnosticSafely(
                                "cube-semantic-canvas session="
                                        + session
                                        + " scanView="
                                        + views
                                        + " view="
                                        + ordinal
                                        + " canvas="
                                        + available
                                        + " shown="
                                        + shown
                                        + " visible="
                                        + visible
                                        + " windowVisible="
                                        + windowVisible
                                        + " attached="
                                        + attached
                                        + " alphaPositive="
                                        + alphaPositive
                                        + " picture="
                                        + picture
                                        + " bitmap="
                                        + bitmap
                                        + " demotion="
                                        + demotion
                                        + " commandMode="
                                        + commandMode
                                        + " commands="
                                        + Math.min(count, 65535)
                                        + " inspected="
                                        + examined
                                        + " complete="
                                        + (commands != null && examined == count && !cancelled)
                                        + " budgetLimit="
                                        + (limit < count)
                                        + " textInline="
                                        + inline
                                        + " textLayout="
                                        + layouts
                                        + " richInline="
                                        + rich
                                        + " textRead="
                                        + textRead
                                        + " commandTypesFingerprint="
                                        + (commands == null
                                                ? "unavailable"
                                                : Policy.classSequenceFingerprint(
                                                        names, cancelled ? -1 : count)));
                    } catch (ReflectiveOperationException
                            | RuntimeException
                            | LinkageError ignored) {
                        logDiagnosticSafely(
                                "cube-semantic-canvas session="
                                        + session
                                        + " scanView="
                                        + views
                                        + " readFailed=true");
                    }
                }
                if (cancelled) break;
                if (view instanceof ViewGroup group) {
                    for (int index = 0;
                            index < group.getChildCount() && pending.size() < 128;
                            index++) pending.addLast(group.getChildAt(index));
                }
            }
            logDiagnosticSafely(
                    "cube-semantic-end session="
                            + session
                            + " visited="
                            + visited
                            + " views="
                            + views
                            + " inspected="
                            + inspected
                            + " treeLimit="
                            + (!pending.isEmpty() && visited >= 128)
                            + " viewLimit="
                            + (!pending.isEmpty() && views >= 32)
                            + " commandBudgetExhausted="
                            + (inspected >= MAX_SEMANTIC_COMMANDS)
                            + " cancelled="
                            + cancelled);
        } catch (RuntimeException | LinkageError ignored) {
            logDiagnosticSafely("cube-semantic-end session=" + session + " readFailed=true");
        }
    }

    synchronized void clear() {
        activity = null;
        renderRoot = null;
        cubeRoot = null;
        ownerStillCurrent = null;
        nodes.clear();
        events = 0;
        commandEvents = 0;
        canvasSamples = 0;
        skippedNonMain = 0;
        sampledExisting = false;
        sampledSemantic = false;
        controlledDiscovery = false;
        session++;
    }

    @Override
    public void close() {
        clear();
    }

    // 已有对象采样与元数据定位始终重新核对当前 owner。

    private boolean isDiscoveryOwnerCurrent() {
        DisplayConfig config = configuration.get();
        return config != null
                && config.diagnostics
                && controlledDiscovery
                && activity != null
                && cubeRoot != null
                && ownerStillCurrent != null
                && ownerStillCurrent.getAsBoolean()
                && isDescendantOf(cubeRoot, renderRoot)
                && isDescendantOf(renderRoot, activity.getWindow().getDecorView());
    }

    private boolean acceptsCurrentView(View view) {
        DisplayConfig config = configuration.get();
        if (config == null
                || !config.diagnostics
                || activity == null
                || ownerStillCurrent == null
                || !ownerStillCurrent.getAsBoolean()
                || !isDescendantOf(cubeRoot, renderRoot)
                || !isDescendantOf(renderRoot, activity.getWindow().getDecorView())) {
            clear();
            return false;
        }
        return events < MAX_EVENTS && view != null && isDescendantOf(view, cubeRoot);
    }

    private Node findOrCreateNode(View view) throws ReflectiveOperationException {
        Node existing = nodes.get(view);
        if (existing == null && nodes.size() >= MAX_VIEWS) return null;
        String hash = "unavailable";
        String source = "unavailable", instanceHash = "unavailable";
        Object tag = view.getTag();
        // getViewNodeID的当前实现会在null tag上解引用；仅从已知CKViewTag读取同一字段。
        if (tag != null && VIEW_TAG.equals(tag.getClass().getName())) {
            hash = Policy.hash(readField(tag, "nodeId"));
            source = "view-tag";
        }
        if (isSupportedContainer(view)) {
            instanceHash = Policy.hash(readField(view, "instanceId"));
            if ("unavailable".equals(hash)) {
                hash = Policy.hash(readField(view, "nodeId"));
                source = "container-field";
            }
        }
        if (existing != null) {
            if (!hash.equals(existing.hash)) {
                existing.label = "none";
                existing.numeric = false;
                existing.labeledAmount = false;
                existing.mask = false;
                existing.hidden = false;
            }
            existing.hash = hash;
            existing.source = source;
            existing.instanceHash = instanceHash;
            return existing;
        }
        Node created = new Node(nodes.size() + 1, hash, source, instanceHash);
        nodes.put(view, created);
        return created;
    }

    private int resolveParentOrdinal(View view) throws ReflectiveOperationException {
        ViewParent parent = view.getParent();
        if (parent instanceof View parentView && isDescendantOf(parentView, cubeRoot)) {
            Node node = findOrCreateNode(parentView);
            return node == null ? 0 : node.ordinal;
        }
        return 0;
    }

    private String resolveSemanticLabel(View view) throws ReflectiveOperationException {
        View current = view;
        for (int depth = 0; current != null && depth < 3; depth++) {
            Node node = findOrCreateNode(current);
            if (node != null) refreshDescriptionFlags(current, node);
            if (node != null && (node.hidden || node.mask)) return "none";
            if (node != null && !"none".equals(node.label)) return node.label;
            if (current == cubeRoot) break;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return "none";
    }

    private static void refreshDescriptionFlags(View view, Node node) {
        if (!isSupportedContainer(view)) return;
        Object description = view.getContentDescription();
        node.label = CubeTextPolicy.label(description);
        node.numeric = CubeTextPolicy.isAmount(description);
        node.mask = CubeTextPolicy.isMaskedAmount(description);
        node.labeledAmount = CubeTextPolicy.hasLabeledAmount(description);
    }

    private static TextObservation readTextObservation(
            Object command, String type, boolean visible, DisplayConfig config)
            throws ReflectiveOperationException {
        TextObservation result = new TextObservation();
        if ("text-layout".equals(type)) {
            Object value = readField(command, "layout");
            if (value instanceof Layout layout) {
                result.lines = Math.max(0, Math.min(layout.getLineCount(), 32));
                if (visible) {
                    String text = readBoundedText(layout.getText());
                    result.textRead = text != null;
                    result.publicRole = Policy.publicRole(text);
                    result.value =
                            CubeTextPolicy.classifyText(
                                    text,
                                    config == null
                                            ? null
                                            : config.amount(DisplayConfig.Kind.BALANCE));
                }
            }
        } else if ("text-inline".equals(type) || "rich-text-inline".equals(type)) {
            Object value = readField(command, "lines");
            if (value instanceof Object[] lines) {
                result.lines = Math.min(lines.length, 32);
                if (visible && lines.length <= 8) {
                    StringBuilder text = new StringBuilder();
                    for (Object line : lines) {
                        if (line == null
                                || !"com.antfin.cube.cubecore.draw.CRTextLineInfo"
                                        .equals(line.getClass().getName())) return result;
                        Object raw = readField(line, "text");
                        if (!(raw instanceof String part) || text.length() + part.length() > 256)
                            return result;
                        text.append(part);
                    }
                    result.textRead = true;
                    result.publicRole = Policy.publicRole(text.toString());
                    result.value =
                            CubeTextPolicy.classifyText(
                                    text.toString(),
                                    config == null
                                            ? null
                                            : config.amount(DisplayConfig.Kind.BALANCE));
                }
            }
        }
        return result;
    }

    private static boolean isSupportedContainer(View view) {
        return hasClassInHierarchy(
                view, "com.antfin.cube.cubecore.component.container.CKContainerView");
    }

    private static boolean hasClassInHierarchy(Object object, String name) {
        for (Class<?> type = object == null ? null : object.getClass();
                type != null;
                type = type.getSuperclass()) {
            if (name.equals(type.getName())) return true;
        }
        return false;
    }

    private static boolean isDescendantOf(View child, View parent) {
        if (child == null || parent == null) return false;
        View current = child;
        for (int depth = 0; current != null && depth < 96; depth++) {
            if (current == parent) return true;
            ViewParent next = current.getParent();
            current = next instanceof View ? (View) next : null;
        }
        return false;
    }

    private static Object readField(Object object, String name)
            throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException();
    }

    private static String readBoundedText(CharSequence text) {
        if (text == null || text.length() > 256) return null;
        StringBuilder value = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) value.append(text.charAt(index));
        return value.toString();
    }

    private boolean isMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) return true;
        if (cubeRoot != null) skippedNonMain = Math.min(1024, skippedNonMain + 1);
        return false;
    }

    // 所有日志路径保留独立事件预算，并只接收固定分类结果。

    private void logMetadata(String line) {
        if (events++ >= MAX_EVENTS) return;
        try {
            Log.i(TAG, line);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void logCommandSample(String line) {
        if (commandEvents++ >= MAX_COMMAND_EVENTS) return;
        try {
            Log.i(TAG, line);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static void logDiagnosticSafely(String line) {
        try {
            Log.i(TAG, line);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void stopAfterFailure() {
        clear();
        try {
            Log.i(TAG, "cube-diagnostic unavailable=true");
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static final class Node {
        final int ordinal;
        String hash, source, instanceHash;
        String label = "none";
        boolean numeric, labeledAmount, mask, hidden;

        Node(int ordinal, String hash, String source, String instanceHash) {
            this.ordinal = ordinal;
            this.hash = hash;
            this.source = source;
            this.instanceHash = instanceHash;
        }
    }

    private static final class TextObservation {
        int lines;
        String publicRole = "none";
        boolean textRead;
        CubeTextPolicy.Classification value = CubeTextPolicy.classifyText(null, null);
    }

    /** 所有可输出字符串均由固定枚举或固定API的有界摘要产生。 */
    static final class Policy {
        static String publicRole(Object value) {
            if (!(value instanceof String text)) return "none";
            return switch (text.trim()) {
                case "充值" -> "deposit";
                case "提现" -> "withdraw";
                case "明细", "余额明细" -> "records";
                case "设置", "余额设置", "余额支付设置", "余额支付管理", "支付设置" -> "settings";
                case "请输入支付密码", "安全验证", "验证身份", "指纹验证", "面容验证" -> "security-challenge";
                case "请选择银行卡" -> "card-picker";
                case "加载中", "请稍候", "请稍后", "点击重试" -> "loading-or-retry";
                default -> "none";
            };
        }

        static boolean identity(String appId, long version) {
            return "77700300".equals(appId) && version == 212370L;
        }

        static boolean discovery(
                String appId, long version, String resource, String routeFingerprint) {
            return identity(appId, version)
                    && "0.1.2610191118.5".equals(resource)
                    && "bf0155f27c322f06e77362dbb3c41988efdae4e371fd0cda6f04d89a729a9eef"
                            .equals(routeFingerprint);
        }

        static boolean commandTruncated(int count) {
            return count > MAX_COMMANDS;
        }

        static boolean budgetTruncated(int count, int emitted) {
            return emitted < Math.min(Math.max(0, count), MAX_COMMANDS);
        }

        static boolean complete(int count, int emitted) {
            return count >= 0 && count <= MAX_COMMANDS && emitted == count;
        }

        static boolean stage(String stage) {
            return "flush".equals(stage)
                    || "bind".equals(stage)
                    || "update".equals(stage)
                    || "existing".equals(stage);
        }

        static String role(Object value) {
            return value instanceof String text
                            && (text.equals("text")
                                    || text.equals("button")
                                    || text.equals("group")
                                    || text.equals("header")
                                    || text.equals("heading")
                                    || text.equals("none"))
                    ? text
                    : "other";
        }

        static String hash(Object value) {
            if (!(value instanceof String text) || text.isEmpty() || text.length() > 256)
                return "unavailable";
            try {
                byte[] digest =
                        MessageDigest.getInstance("SHA-256")
                                .digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder output = new StringBuilder(64);
                for (byte part : digest)
                    output.append(String.format(Locale.ROOT, "%02x", part & 255));
                return output.toString();
            } catch (Exception ignored) {
                return "unavailable";
            }
        }

        static String classSequenceFingerprint(String[] names, int declaredCount) {
            if (names == null
                    || names.length != declaredCount
                    || declaredCount < 0
                    || declaredCount > MAX_SEMANTIC_COMMANDS) return "unavailable";
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (String name : names) {
                    if (name == null
                            || name.isEmpty()
                            || name.length() > 256
                            || !name.matches("[A-Za-z0-9_.$\\[;]+")) return "unavailable";
                    byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
                    digest.update((byte) (encoded.length >>> 8));
                    digest.update((byte) encoded.length);
                    digest.update(encoded);
                }
                StringBuilder result = new StringBuilder(64);
                for (byte value : digest.digest()) {
                    result.append(Character.forDigit((value >>> 4) & 15, 16));
                    result.append(Character.forDigit(value & 15, 16));
                }
                return result.toString();
            } catch (Exception ignored) {
                return "unavailable";
            }
        }
    }
}
