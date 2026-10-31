package io.github.fengying.alipaydisplay;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 余额单一金额的绘制期私有副本；原canvas/命令/文本保持宿主所有。 */
final class BalanceCanvasSession implements AutoCloseable {
    private static final String TAG = "AlipayDisplayDiag";
    private static final String NG = "com.antfin.cube.cubebridge.view.CubeViewNgImpl";
    private static final String CK_CANVAS = "com.antfin.cube.platform.context.CKCanvas";
    private static final String INLINE = "com.antfin.cube.cubecore.draw.CRTextViewDrawCmdInline";
    private static final String LINE = "com.antfin.cube.cubecore.draw.CRTextLineInfo";
    private static final String RUN = "com.antfin.cube.cubecore.draw.CRTextLineRunInfo";
    private static final String STYLE = "com.antfin.cube.cubecore.draw.CRTextStyle";
    private final Supplier<DisplayConfig> configuration;
    private final BooleanSupplier configPending;
    private View ngRoot;
    private BooleanSupplier strictOwnerCurrent;
    private boolean closed;
    private int failureLogs, cloneLogs, leaseLogs, unprovenDraws;
    private int knownDraws,
            replacedDraws,
            blankedDraws,
            completedDraws,
            unknownDraws,
            unreplacedDraws;
    private int fencedDraws, fencedCompletedDraws;
    private final AuditBudget auditBudget = new AuditBudget();
    private final IdentityHashMap<View, Object> inFlight = new IdentityHashMap<>();
    private final IdentityHashMap<View, FontRetry> fontRetries = new IdentityHashMap<>();

    /** owner谓词必须持续核验已取证app/宿主/资源/route及同App/Page/Render和完整导航。 */
    BalanceCanvasSession(
            Supplier<DisplayConfig> configuration,
            View ngRoot,
            BooleanSupplier strictOwnerCurrent) {
        this(configuration, ngRoot, strictOwnerCurrent, () -> false);
    }

    BalanceCanvasSession(
            Supplier<DisplayConfig> configuration,
            View ngRoot,
            BooleanSupplier strictOwnerCurrent,
            BooleanSupplier configPending) {
        this.configuration = configuration;
        this.ngRoot = ngRoot;
        this.strictOwnerCurrent = strictOwnerCurrent;
        this.configPending = configPending;
    }

    // 会话入口按运行时调用顺序排列，内部校验与绘制实现放在下方。

    /** 仅判断Ng祖先关系，不读取页面业务值或依赖active Activity标志。 */
    boolean owns(View view) {
        if (!isMainThread() || closed || ngRoot == null || !NG.equals(ngRoot.getClass().getName()))
            return false;
        View current = view;
        for (int depth = 0; current != null && depth < 96; depth++) {
            if (current == ngRoot) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    boolean isCurrent() {
        try {
            return isMainThread()
                    && !closed
                    && strictOwnerCurrent != null
                    && strictOwnerCurrent.getAsBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    Shape inspectShape(View view, Object canvas) {
        return inspectShape(view, canvas, isConfigurationPending());
    }

    /** 仅创建显示专属副本；此方法不把副本交给SDK，也不写源字段。 */
    Object prepare(View view, Object canvas) {
        Shape shape = inspectShape(view, canvas);
        try {
            return createDisplayCanvas(view, canvas, shape, configuration.get(), false, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            reportInspectionFailure();
            return null;
        }
    }

    /** 原onDraw仅执行一次；finally必须close，正常返回后另行markDrawCompleted。 */
    DrawLease beforeDraw(View view) {
        boolean knownAttempt = false;
        try {
            if (!owns(view)) return null;
            DisplayConfig config = configuration.get();
            boolean pendingProof = isConfigurationPending();
            if (config == null || !config.enabled && !config.diagnostics && !pendingProof)
                return null;
            if (!isCurrent()) {
                if (config != null && config.diagnostics && unprovenDraws++ < 8)
                    logDiagnosticSafely("balance-shape unprovenFirstDraw=true");
                return null;
            }
            Field slot = findAccessibleField(view.getClass(), "canvas");
            Object original = slot.get(view);
            Object previousOwned = inFlight.get(view);
            if (Policy.ownedReentry(original, previousOwned)) return null;
            Shape shape = inspectShape(view, original, pendingProof);
            if (!config.enabled && !pendingProof) {
                cancelFontRetry(view);
                return null;
            }
            if (shape.shapeKnown) {
                knownDraws++;
                knownAttempt = true;
            } else unknownDraws++;
            boolean configBlank = Policy.protectPending(pendingProof, shape.blankProof);
            boolean blankMain =
                    Policy.shouldBlank(shape.blankProof, shape.cloneCandidate, pendingProof);
            String blankReason =
                    Policy.blankReason(
                            configBlank,
                            blankMain,
                            shape.fontAvailable,
                            shape.fontCacheStable,
                            shape.fitChecked,
                            shape.configFits);
            Object own =
                    createDisplayCanvas(view, original, shape, config, blankMain, pendingProof);
            DisplayConfig latest = configuration.get();
            if (own == null
                    || !isCurrent()
                    || slot.get(view) != original
                    || latest == null
                    || !Policy.drawProofCurrent(
                            config.revision,
                            latest.revision,
                            pendingProof,
                            isConfigurationPending(),
                            latest.enabled)) {
                if (shape.shapeKnown) unreplacedDraws++;
                return null;
            }
            slot.set(view, own);
            if (slot.get(view) != own) {
                if (knownAttempt) unreplacedDraws++;
                return null;
            }
            inFlight.put(view, own);
            DrawLease lease =
                    new DrawLease(
                            this, view, slot, original, own, previousOwned, blankMain, blankReason);
            if (blankMain) blankedDraws++;
            else replacedDraws++;
            try {
                if (blankMain
                        && !pendingProof
                        && !isConfigurationPending()
                        && Policy.retryForBlank(
                                shape.blankProof,
                                shape.fontAvailable,
                                shape.fontCacheStable,
                                shape.fitChecked,
                                shape.configFits,
                                shape.metricsPending)) requestFontRetry(view, config.revision);
                else if (!blankMain) cancelFontRetry(view);
                else stopFontRetry(view);
            } catch (RuntimeException | LinkageError ignored) {
                reportInspectionFailure();
            }
            return lease;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            if (knownAttempt) unreplacedDraws++;
            reportInspectionFailure();
        }
        return null;
    }

    /** 仅在beforeDraw未返回lease时使用；不写宿主对象，不把自有副本重入当作原画。 */
    boolean shouldSuppressDraw(View view) {
        try {
            if (!owns(view) || !isCurrent()) return false;
            DisplayConfig config = configuration.get();
            boolean pendingProof = isConfigurationPending();
            if (config == null || !config.enabled && !pendingProof) return false;
            Field slot = findAccessibleField(view.getClass(), "canvas");
            Object source = slot.get(view);
            boolean ownReentry = Policy.ownedReentry(source, inFlight.get(view));
            if (ownReentry) return false;
            Shape shape = inspectShape(view, source, pendingProof);
            DisplayConfig latest = configuration.get();
            boolean stillPending = isConfigurationPending();
            return latest != null
                    && Policy.drawProofCurrent(
                            config.revision,
                            latest.revision,
                            pendingProof,
                            stillPending,
                            latest.enabled)
                    && Policy.suppressKnown(
                            latest.enabled,
                            stillPending,
                            isCurrent() && owns(view),
                            Policy.fenceShape(
                                    shape.shapeKnown,
                                    shape.commandMode,
                                    shape.noPicture,
                                    shape.noBitmap,
                                    shape.noDemotion),
                            source == slot.get(view),
                            ownReentry);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            reportInspectionFailure();
            return false;
        }
    }

    /** 透明层/裁切已保护，或双重保护失败而跳过原draw时计数；completed只表示原draw正常返回。 */
    void markDrawSuppressed(boolean completed) {
        if (!isMainThread()) return;
        fencedDraws++;
        if (completed) fencedCompletedDraws++;
        try {
            DisplayConfig config = configuration.get();
            if (config != null && config.diagnostics && leaseLogs++ < 96)
                logDiagnosticSafely(
                        "balance-fence suppressed=true completed="
                                + completed
                                + " fenced="
                                + fencedDraws
                                + " fencedCompleted="
                                + fencedCompletedDraws
                                + " known="
                                + knownDraws
                                + " unreplaced="
                                + unreplacedDraws);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            DisplayConfig config = configuration.get();
            if (config != null && config.diagnostics)
                logDiagnosticSafely(
                        "balance-draw-summary known="
                                + knownDraws
                                + " replaced="
                                + replacedDraws
                                + " blanked="
                                + blankedDraws
                                + " completed="
                                + completedDraws
                                + " unreplaced="
                                + unreplacedDraws
                                + " fenced="
                                + fencedDraws
                                + " fencedCompleted="
                                + fencedCompletedDraws
                                + " unknown="
                                + unknownDraws);
        } catch (RuntimeException | LinkageError ignored) {
        }
        closed = true;
        for (FontRetry retry : new ArrayList<>(fontRetries.values())) retry.cancel();
        fontRetries.clear();
        ngRoot = null;
        strictOwnerCurrent = null;
        inFlight.clear();
    }

    /** 只读累计计数，便于在结构诊断的输出高峰后复核实际绘制覆盖。 */
    String diagnosticSummary() {
        return "balance-draw-summary final=false current="
                + isCurrent()
                + " closed="
                + closed
                + " shown="
                + (ngRoot != null && ngRoot.isShown())
                + " known="
                + knownDraws
                + " replaced="
                + replacedDraws
                + " blanked="
                + blankedDraws
                + " completed="
                + completedDraws
                + " unreplaced="
                + unreplacedDraws
                + " unknown="
                + unknownDraws;
    }

    // 绘制准备：先确认当前源结构，再创建仅主金额不同的私有副本。

    private Shape inspectShape(View view, Object canvas, boolean pendingProof) {
        Shape result = new Shape();
        try {
            if (!isMainThread() || closed) return result;
            DisplayConfig config = configuration.get();
            if (config == null || !config.enabled && !config.diagnostics && !pendingProof)
                return result;
            result.configPending = pendingProof;
            result.ownerCurrent = owns(view) && isCurrent();
            result.shown = result.ownerCurrent && view.isShown();
            if (!result.shown || canvas == null || !CK_CANVAS.equals(canvas.getClass().getName()))
                return finishShapeInspection(result, config);
            result.commandMode = Boolean.TRUE.equals(readField(canvas, "isCmd"));
            result.noPicture = readField(canvas, "mPicture") == null;
            result.noBitmap = readField(canvas, "bitmap") == null;
            result.noDemotion = !Boolean.TRUE.equals(readField(canvas, "demotion"));
            if (!(readField(canvas, "cmdList") instanceof ArrayList<?> commands))
                return finishShapeInspection(result, config);
            result.commands = commands.size();
            if (result.commands > 111) return finishShapeInspection(result, config);
            String[] classNames = new String[commands.size()];
            int[] textOrder = new int[8];
            for (int index = 0; index < commands.size(); index++) {
                Object command = commands.get(index);
                classNames[index] = command == null ? null : command.getClass().getName();
                String kind = CubeTextPolicy.commandKind(classNames[index]);
                if ("text-layout".equals(kind) || "rich-text-inline".equals(kind))
                    result.extraTextCommands++;
                if (INLINE.equals(classNames[index])) {
                    if (result.textCommands < textOrder.length)
                        textOrder[result.textCommands] = index;
                    result.textCommands++;
                }
            }
            if (result.commands != 57 && result.commands != 111)
                return finishShapeInspection(result, config);
            result.commandTypesFingerprint = Policy.classSequenceFingerprint(classNames);
            result.commandTypesPinned =
                    Policy.pinnedSequence(result.commands, result.commandTypesFingerprint);
            if (!result.commandTypesPinned) return finishShapeInspection(result, config);
            Object amountCommand = null;
            String amountText = null;
            for (int index = 0; index < commands.size(); index++) {
                Object command = commands.get(index);
                if (command == null || !INLINE.equals(command.getClass().getName())) continue;
                String value = readSingleLineText(command);
                CubeTextPolicy.Classification classification =
                        CubeTextPolicy.classifyText(
                                value, config.amount(DisplayConfig.Kind.BALANCE));
                if ("available-balance".equals(classification.label)) {
                    result.titleCount++;
                    result.titleIndex = index;
                }
                if (classification.pureAmount) {
                    result.amountCount++;
                    result.amountIndex = index;
                    amountCommand = command;
                    amountText = value;
                }
                if (classification.mask) result.maskCount++;
            }
            result.textOrderKnown =
                    Policy.textOrderKnown(result.commands, textOrder, result.textCommands);
            result.shapeKnown =
                    Policy.shape(
                                    result.commands,
                                    result.titleCount,
                                    result.amountCount,
                                    result.titleIndex,
                                    result.amountIndex,
                                    result.textOrderKnown,
                                    result.maskCount)
                            && result.extraTextCommands == 0
                            && result.commandTypesPinned;
            if (!result.shapeKnown || amountCommand == null || amountText == null)
                return finishShapeInspection(result, config);
            Object rawLines = readField(amountCommand, "lines");
            if (!(rawLines instanceof Object[] lines)) return finishShapeInspection(result, config);
            result.lines = lines.length;
            result.oneLine = lines.length == 1;
            if (!result.oneLine || lines[0] == null || !LINE.equals(lines[0].getClass().getName()))
                return finishShapeInspection(result, config);
            Object line = lines[0], rawRuns = readField(line, "runs");
            if (!(rawRuns instanceof Object[] runs)) return finishShapeInspection(result, config);
            result.runs = runs.length;
            result.oneRun = runs.length == 1;
            if (!result.oneRun || runs[0] == null || !RUN.equals(runs[0].getClass().getName()))
                return finishShapeInspection(result, config);
            Object run = runs[0], style = readField(amountCommand, "textStyle");
            if (style == null || !STYLE.equals(style.getClass().getName()))
                return finishShapeInspection(result, config);
            int start = readIntField(run, "start"),
                    end = readIntField(run, "end"),
                    typefaceId = readIntField(run, "typefaceId");
            result.runCoversText = start == 0 && end == amountText.length();
            result.needDraw = Boolean.TRUE.equals(readField(run, "needDraw"));
            result.typefaceInfoZero = readIntField(run, "typefaceInfo") == 0;
            result.styleNormal = readIntField(style, "fontStyleIndex") == 0;
            int weight = readIntField(style, "fontWeight");
            result.weightNormal =
                    weight
                            == enumOrdinal(
                                    style.getClass().getClassLoader(),
                                    "com.antfin.cube.cubecore.layout.style.CKStyle$CKFontWeight",
                                    "MFFONT_WEIGHT_400");
            result.letterSpacingZero = readFloatField(style, "letterSpacing") == 0f;
            result.noImage = !Boolean.TRUE.equals(readField(style, "isImage"));
            result.noShadow = !Boolean.TRUE.equals(readField(style, "isShadow"));
            result.decoration = Policy.decoration(readIntField(style, "textDecoration"));
            result.noDecoration = "none".equals(result.decoration);
            result.noHighlight = isNullOrEmptyArray(readField(amountCommand, "textHighlightInfo"));
            result.symbolZero = readIntField(amountCommand, "symbolType") == 0;
            result.noLineDecoration =
                    !Boolean.TRUE.equals(readField(line, "drawBorder"))
                            && !Boolean.TRUE.equals(readField(line, "drawBackground"));
            result.noBoxShadow = isNullOrEmptyArray(readField(amountCommand, "boxShadows"));
            result.noBackgroundStyles = isNullOrEmptyArray(readField(amountCommand, "bgStyles"));
            result.visiblePaint =
                    Policy.positiveAlpha(readFloatField(style, "alpha"))
                            && Policy.positiveAlpha(readFloatField(amountCommand, "alpha"))
                            && (readIntField(style, "textColor") >>> 24) != 0;
            result.noComplexStyle =
                    result.noImage
                            && result.noShadow
                            && result.noDecoration
                            && result.noHighlight
                            && result.symbolZero
                            && result.noLineDecoration
                            && result.noBoxShadow
                            && result.noBackgroundStyles;
            result.blankProof =
                    result.shapeKnown
                            && result.ownerCurrent
                            && result.shown
                            && result.commandMode
                            && result.noPicture
                            && result.noBitmap
                            && result.noDemotion
                            && result.oneLine
                            && result.oneRun
                            && result.runCoversText
                            && result.needDraw
                            && result.typefaceInfoZero
                            && result.styleNormal
                            && result.letterSpacingZero
                            && result.visiblePaint
                            && result.noComplexStyle;
            Class<?> fonts =
                    Class.forName(
                            "com.antfin.cube.platform.api.CKFontManager",
                            false,
                            amountCommand.getClass().getClassLoader());
            Method getter = fonts.getMethod("getTypeface", int.class);
            Object cached = typefaceId < 0 ? null : getter.invoke(null, typefaceId);
            result.fontAvailable = cached instanceof Typeface;
            float size = readFloatField(style, "textSize"),
                    contentWidth = readFloatField(amountCommand, "contentWidth");
            float lineX = readFloatField(line, "originX"),
                    lineWidth = readFloatField(line, "width"),
                    runWidth = readFloatField(run, "width");
            result.metricsFinite = Policy.metrics(size, contentWidth, lineX, lineWidth, runWidth);
            if (result.fontAvailable && result.metricsFinite) {
                TextPaint paint = createDisplayPaint(style, run, (Typeface) cached);
                if (paint != null) {
                    result.paintSetupKnown = true;
                    result.paintLinear = paint.isLinearText();
                    result.paintSubpixel = paint.isSubpixelText();
                    result.paintFakeBold = paint.isFakeBoldText();
                    result.paintSkewZero = paint.getTextSkewX() == 0f;
                    result.paintScaleOne = paint.getTextScaleX() == 1f;
                    float sourceWidth = paint.measureText(amountText);
                    float configuredWidth =
                            paint.measureText(config.formatted(DisplayConfig.Kind.BALANCE));
                    result.measuredWidthsFinite =
                            Policy.measuredWidths(sourceWidth, configuredWidth);
                    result.sourceMatchesRun = Policy.widthMatches(sourceWidth, runWidth);
                    result.sourceMatchesLine = Policy.widthMatches(sourceWidth, lineWidth);
                    result.lineMatchesRun = Policy.widthMatches(lineWidth, runWidth);
                    result.sourceMetricsMatch = result.sourceMatchesRun && result.sourceMatchesLine;
                    result.fontCacheStable = getter.invoke(null, typefaceId) == cached;
                    result.fitChecked = Float.isFinite(configuredWidth) && configuredWidth > 0;
                    result.configFits = Policy.fits(lineX, configuredWidth, contentWidth);
                }
            }
            boolean measuredShape =
                    result.blankProof
                            && result.fontAvailable
                            && result.fontCacheStable
                            && result.paintSetupKnown
                            && result.paintLinear
                            && !result.paintSubpixel
                            && result.paintSkewZero
                            && result.paintScaleOne
                            && result.metricsFinite
                            && result.measuredWidthsFinite
                            && result.configFits;
            result.cloneCandidate = measuredShape && result.sourceMetricsMatch;
            result.metricsPending = Policy.metricsPending(measuredShape, result.sourceMetricsMatch);
            return finishShapeInspection(result, config);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            result.readFailed = true;
            try {
                return finishShapeInspection(result, configuration.get());
            } catch (RuntimeException | LinkageError ignoredAgain) {
                return result;
            }
        }
    }

    private boolean isConfigurationPending() {
        try {
            return !closed && configPending != null && configPending.getAsBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private Object createDisplayCanvas(
            View view,
            Object canvas,
            Shape shape,
            DisplayConfig config,
            boolean blankMain,
            boolean pendingProof)
            throws ReflectiveOperationException {
        boolean candidate = blankMain ? shape.blankProof : !pendingProof && shape.cloneCandidate;
        if (config == null
                || !config.enabled && !pendingProof
                || !candidate
                || !Policy.pendingProofCurrent(
                        pendingProof, isConfigurationPending(), config.enabled)
                || !owns(view)
                || !isCurrent()) return null;
        ArrayList<?> sourceList = (ArrayList<?>) readField(canvas, "cmdList");
        Object[] originalEntries = sourceList.toArray();
        Object sourceCommand = sourceList.get(shape.amountIndex);
        Object[] sourceLines = (Object[]) readField(sourceCommand, "lines");
        Object sourceLine = sourceLines[0];
        Object[] sourceRuns = (Object[]) readField(sourceLine, "runs");
        Object sourceRun = sourceRuns[0], sourceStyle = readField(sourceCommand, "textStyle");
        String originalText = (String) readField(sourceLine, "text");
        String configured = blankMain ? null : config.formatted(DisplayConfig.Kind.BALANCE);
        float width = Float.NaN;
        if (!blankMain) {
            Method fontGetter =
                    Class.forName(
                                    "com.antfin.cube.platform.api.CKFontManager",
                                    false,
                                    sourceCommand.getClass().getClassLoader())
                            .getMethod("getTypeface", int.class);
            int typefaceId = readIntField(sourceRun, "typefaceId");
            Object cached = fontGetter.invoke(null, typefaceId);
            if (!(cached instanceof Typeface typeface)) return null;
            TextPaint paint = createDisplayPaint(sourceStyle, sourceRun, typeface);
            if (paint == null) return null;
            width = paint.measureText(configured);
            float sourceWidth = paint.measureText(originalText);
            if (!Policy.measuredWidths(sourceWidth, width)
                    || fontGetter.invoke(null, typefaceId) != cached
                    || !Policy.fits(
                            readFloatField(sourceLine, "originX"),
                            width,
                            readFloatField(sourceCommand, "contentWidth"))
                    || !Policy.widthMatches(sourceWidth, readFloatField(sourceRun, "width"))
                    || !Policy.widthMatches(sourceWidth, readFloatField(sourceLine, "width")))
                return null;
        }
        Object rawW = readField(canvas, "w"), rawH = readField(canvas, "h");
        if (!(rawW instanceof Double w) || !(rawH instanceof Double h) || !Policy.canvasSize(w, h))
            return null;
        Object ownCanvas =
                canvas.getClass()
                        .getConstructor(
                                double.class,
                                double.class,
                                boolean.class,
                                boolean.class,
                                boolean.class)
                        .newInstance(
                                w,
                                h,
                                true,
                                false,
                                Boolean.TRUE.equals(readField(canvas, "opaque")));
        if (ownCanvas == canvas
                || !Boolean.TRUE.equals(readField(ownCanvas, "isCmd"))
                || readField(ownCanvas, "mPicture") != null
                || readField(ownCanvas, "bitmap") != null
                || Boolean.TRUE.equals(readField(ownCanvas, "demotion"))) return null;
        Object rawList = readField(ownCanvas, "cmdList");
        if (!(rawList instanceof ArrayList<?> emptyList)
                || emptyList == sourceList
                || !emptyList.isEmpty()) return null;
        Object ownStyle =
                copyVerifiedDrawBean(
                        sourceStyle, STYLE, "(Ljava/lang/String;IIIIIFZIFZFFFFZIFFFFFFZI)V");
        Object ownRun = null, ownLine = null, ownRuns = null;
        // 已核对两条inline绘制分支均按lines.length迭代；空数组仅抑制这个主金额命令。
        Object ownLines = Array.newInstance(sourceLine.getClass(), blankMain ? 0 : 1);
        if (!blankMain) {
            ownRun = copyVerifiedDrawBean(sourceRun, RUN, "(IIIFZI)V");
            writePrivateField(ownRun, "start", 0);
            writePrivateField(ownRun, "end", configured.length());
            writePrivateField(ownRun, "width", width);
            ownRuns = Array.newInstance(sourceRun.getClass(), 1);
            Array.set(ownRuns, 0, ownRun);
            ownLine =
                    copyVerifiedDrawBean(
                            sourceLine,
                            LINE,
                            "(Ljava/lang/String;FFFFFZZ[Lcom/antfin/cube/cubecore/draw/CRTextLineRunInfo;)V");
            writePrivateField(ownLine, "text", configured);
            writePrivateField(ownLine, "width", width);
            writePrivateField(ownLine, "runs", ownRuns);
            Array.set(ownLines, 0, ownLine);
        }
        Object ownCommand =
                copyVerifiedDrawBean(
                        sourceCommand,
                        INLINE,
                        "(JLjava/lang/Object;[Lcom/antfin/cube/cubecore/draw/CRTextLineInfo;Lcom/antfin/cube/cubecore/draw/CRTextStyle;ZFFFFFFFFFI[Lcom/antfin/cube/cubecore/draw/CRTextHighlightInfo;FFFFFFFFFIIIIFIFIFIFI[Lcom/antfin/cube/cubecore/draw/CKBoxShadow;Z[Lcom/antfin/cube/cubecore/draw/CKViewBgStyle;IFFFF)V");
        writePrivateField(ownCommand, "lines", ownLines);
        writePrivateField(ownCommand, "textStyle", ownStyle);
        writePrivateField(ownCommand, "canvas", ownCanvas);
        writePrivateField(ownCommand, "shadow", null);
        @SuppressWarnings("unchecked")
        ArrayList<Object> ownList = (ArrayList<Object>) rawList;
        for (Object entry : originalEntries) ownList.add(entry);
        ownList.set(shape.amountIndex, ownCommand);
        boolean originalUnchanged =
                readField(canvas, "cmdList") == sourceList
                        && sourceList.get(shape.amountIndex) == sourceCommand
                        && Policy.sameEntries(originalEntries, sourceList.toArray())
                        && readField(sourceCommand, "lines") == sourceLines
                        && sourceLines[0] == sourceLine
                        && readField(sourceLine, "runs") == sourceRuns
                        && sourceRuns[0] == sourceRun
                        && readField(sourceCommand, "textStyle") == sourceStyle
                        && originalText.equals(readField(sourceLine, "text"))
                        && readField(canvas, "bitmap") == null
                        && readField(canvas, "mPicture") == null;
        boolean isolated =
                ownCanvas != canvas
                        && ownList != sourceList
                        && ownCommand != sourceCommand
                        && ownStyle != sourceStyle
                        && ownLines != sourceLines
                        && (blankMain
                                || ownLine != sourceLine
                                        && ownRun != sourceRun
                                        && ownRuns != sourceRuns);
        boolean oneTarget =
                Policy.onlyTargetChanged(originalEntries, ownList.toArray(), shape.amountIndex);
        boolean matches =
                !blankMain
                        && CubeTextPolicy.sameAmount(
                                readField(ownLine, "text"),
                                config.amount(DisplayConfig.Kind.BALANCE));
        boolean targetEmpty = blankMain && Array.getLength(readField(ownCommand, "lines")) == 0;
        if (config.diagnostics && cloneLogs++ < 96)
            logDiagnosticSafely(
                    "balance-clone originalUnchanged="
                            + originalUnchanged
                            + " isolated="
                            + isolated
                            + " onlyMainChanged="
                            + oneTarget
                            + " replacementMatchesConfig="
                            + matches
                            + " blanked="
                            + blankMain
                            + " blankReason="
                            + Policy.blankReason(
                                    pendingProof,
                                    blankMain,
                                    shape.fontAvailable,
                                    shape.fontCacheStable,
                                    shape.fitChecked,
                                    shape.configFits)
                            + " mainLinesEmpty="
                            + targetEmpty
                            + " fitChecked="
                            + !blankMain
                            + " fit="
                            + (!blankMain && shape.configFits)
                            + " sourceOneLine=true");
        if (!originalUnchanged
                || !isolated
                || !oneTarget
                || !(blankMain ? targetEmpty : matches)
                || !isCurrent()) return null;
        DisplayConfig latest = configuration.get();
        return latest != null
                        && Policy.drawProofCurrent(
                                config.revision,
                                latest.revision,
                                pendingProof,
                                isConfigurationPending(),
                                latest.enabled)
                ? ownCanvas
                : null;
    }

    private void finishDraw(
            View view,
            Object own,
            Object previousOwn,
            boolean blanked,
            String blankReason,
            boolean completed,
            boolean restored,
            boolean hostChanged) {
        if (inFlight.get(view) == own) {
            if (previousOwn == null) inFlight.remove(view);
            else inFlight.put(view, previousOwn);
        }
        if (completed) completedDraws++;
        try {
            DisplayConfig config = configuration.get();
            if (config != null && config.diagnostics && leaseLogs++ < 96)
                logDiagnosticSafely(
                        "balance-draw attempted=true completed="
                                + completed
                                + " blanked="
                                + blanked
                                + " blankReason="
                                + blankReason
                                + " restored="
                                + restored
                                + " hostChanged="
                                + hostChanged
                                + " known="
                                + knownDraws
                                + " replaced="
                                + replacedDraws
                                + " blankedCount="
                                + blankedDraws
                                + " unreplaced="
                                + unreplacedDraws
                                + " completedCount="
                                + completedDraws);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 复现已核对的CKDrawCenter单run画笔，仅修改新建TextPaint。 */
    private static TextPaint createDisplayPaint(Object style, Object run, Typeface typeface)
            throws ReflectiveOperationException {
        ClassLoader loader = style.getClass().getClassLoader();
        int weight = readIntField(style, "fontWeight");
        Class<?> weights =
                Class.forName(
                        "com.antfin.cube.cubecore.layout.style.CKStyle$CKFontWeight",
                        false,
                        loader);
        Object[] values = weights.getEnumConstants();
        if (values == null
                || weight < 0
                || weight >= values.length
                || readIntField(style, "fontStyleIndex") != 0) return null;
        float size = readFloatField(style, "textSize"),
                spacing = readFloatField(style, "letterSpacing");
        if (!Float.isFinite(size) || size <= 0 || !Float.isFinite(spacing)) return null;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(size);
        paint.setLinearText(true);
        paint.setLetterSpacing(spacing / size);
        paint.setTextSkewX(0f);
        paint.setFakeBoldText(false);
        paint.setTypeface(typeface);
        Class.forName("com.antfin.cube.cubecore.draw.CKDrawCenter", false, loader)
                .getMethod("applyFontStyle", int.class, boolean.class, int.class, TextPaint.class)
                .invoke(null, weight, false, readIntField(run, "typefaceInfo"), paint);
        return paint;
    }

    private void requestFontRetry(View view, long revision) {
        FontRetry retry = fontRetries.get(view);
        if (retry != null && retry.revision != revision) {
            retry.cancel();
            retry = null;
        }
        if (retry == null) {
            retry = new FontRetry(view, revision);
            fontRetries.put(view, retry);
        }
        retry.schedule();
    }

    private void cancelFontRetry(View view) {
        FontRetry retry = fontRetries.get(view);
        if (retry != null) retry.cancel();
    }

    private void stopFontRetry(View view) {
        FontRetry retry = fontRetries.get(view);
        if (retry != null) retry.stop();
    }

    // 反射边界只接受已核对的构造与字段，不修改宿主源对象。

    /** 只允许已核对的四种纯绘制bean及其已知基类，构造时不传入任何源引用。 */
    private static Object copyVerifiedDrawBean(Object source, String expected, String descriptor)
            throws ReflectiveOperationException {
        Class<?> type = source.getClass();
        if (!expected.equals(type.getName())) throw new ReflectiveOperationException();
        Constructor<?> selected = null;
        for (Constructor<?> constructor : type.getConstructors())
            if (descriptor.equals(constructorSignature(constructor))) selected = constructor;
        if (selected == null) throw new NoSuchMethodException();
        Class<?>[] parameters = selected.getParameterTypes();
        Object[] empty = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            if (parameters[index] == boolean.class) empty[index] = false;
            else if (parameters[index] == int.class) empty[index] = 0;
            else if (parameters[index] == float.class) empty[index] = 0f;
            else if (parameters[index] == long.class) empty[index] = 0L;
            else if (parameters[index] == double.class) empty[index] = 0d;
            else if (parameters[index].isPrimitive()) throw new ReflectiveOperationException();
        }
        Object target = selected.newInstance(empty);
        for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
            Set<String> allowed = verifiedFieldNames(current.getName());
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!allowed.contains(field.getName())) throw new ReflectiveOperationException();
                field.setAccessible(true);
                Object value = field.get(source);
                if (value != null && value.getClass().isArray()) {
                    int length = Array.getLength(value);
                    if (length > 96) throw new ReflectiveOperationException();
                    Object copy = Array.newInstance(value.getClass().getComponentType(), length);
                    System.arraycopy(value, 0, copy, 0, length);
                    value = copy;
                }
                field.set(target, value);
            }
        }
        return target;
    }

    private static Set<String> verifiedFieldNames(String type) throws ReflectiveOperationException {
        String names =
                switch (type) {
                    case STYLE ->
                            "alpha,fontFamily,fontStyleIndex,fontWeight,imageIndex,isImage,isShadow,letterSpacing,paddingBottom,paddingLeft,paddingRight,paddingTop,shadowColor,shadowOffsetX,shadowOffsetY,shadowRadius,textColor,textDecoration,textDecorationColor,textDecorationOffsetIsAuto,textDecorationOffsetValue,textDecorationStyle,textDecorationThickness,textDecorationThicknessIsExplicit,textSize";
                    case RUN -> "end,needDraw,start,typefaceId,typefaceInfo,width";
                    case LINE ->
                            "ascent,drawBackground,drawBorder,height,originX,originY,runs,text,width";
                    case INLINE ->
                            "clipBounds,isInline,lines,symbolRectHeight,symbolRectWidth,symbolRectX,symbolRectY,symbolType,textHighlightInfo,textStyle,verticalOffset";
                    case "com.antfin.cube.cubecore.draw.CRViewDrawCmd" ->
                            "alpha,autoLineBreak,backgroundColor,bgStyles,borderBottomColor,borderBottomStyle,borderBottomWidth,borderLeftColor,borderLeftStyle,borderLeftWidth,borderRightColor,borderRightStyle,borderRightWidth,borderTopColor,borderTopStyle,borderTopWidth,bottomLeftRadiusX,bottomLeftRadiusY,bottomRightRadiusX,bottomRightRadiusY,boxShadows,canvas,contentHeight,contentOriginX,contentOriginY,contentWidth,filterMatrix,height,isDisplayInline,lineBreakMode,lines,mCmd,mCmdId,originX,originY,paddingBottom,paddingLeft,paddingRight,paddingTop,shadow,textIndent,topLeftRadiusX,topLeftRadiusY,topRightRadiusX,topRightRadiusY,width";
                    default -> throw new ReflectiveOperationException();
                };
        Set<String> allowed = new HashSet<>();
        java.util.Collections.addAll(allowed, names.split(","));
        return allowed;
    }

    private static String constructorSignature(Constructor<?> constructor) {
        StringBuilder value = new StringBuilder("(");
        for (Class<?> type : constructor.getParameterTypes()) {
            if (type == boolean.class) value.append('Z');
            else if (type == int.class) value.append('I');
            else if (type == float.class) value.append('F');
            else if (type == double.class) value.append('D');
            else if (type == long.class) value.append('J');
            else if (type.isArray()) value.append(type.getName().replace('.', '/'));
            else value.append('L').append(type.getName().replace('.', '/')).append(';');
        }
        return value.append(")V").toString();
    }

    private static void writePrivateField(Object privateObject, String name, Object value)
            throws ReflectiveOperationException {
        findAccessibleField(privateObject.getClass(), name).set(privateObject, value);
    }

    private static int readIntField(Object object, String name)
            throws ReflectiveOperationException {
        Object value = readField(object, name);
        return value instanceof Integer ? (Integer) value : Integer.MIN_VALUE;
    }

    private static float readFloatField(Object object, String name)
            throws ReflectiveOperationException {
        Object value = readField(object, name);
        return value instanceof Float ? (Float) value : Float.NaN;
    }

    private static Object readField(Object object, String name)
            throws ReflectiveOperationException {
        return findAccessibleField(object.getClass(), name).get(object);
    }

    private static Field findAccessibleField(Class<?> source, String name)
            throws ReflectiveOperationException {
        for (Class<?> type = source; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException();
    }

    private static int enumOrdinal(ClassLoader loader, String name, String constant)
            throws ReflectiveOperationException {
        Object value = Class.forName(name, false, loader).getField(constant).get(null);
        return value instanceof Enum<?> item ? item.ordinal() : Integer.MIN_VALUE;
    }

    private static boolean isNullOrEmptyArray(Object value) {
        return value == null || value instanceof Object[] array && array.length == 0;
    }

    private static String readSingleLineText(Object command) throws ReflectiveOperationException {
        Object value = readField(command, "lines");
        if (!(value instanceof Object[] lines)
                || lines.length != 1
                || lines[0] == null
                || !LINE.equals(lines[0].getClass().getName())) return null;
        Object raw = readField(lines[0], "text");
        return raw instanceof String text && text.length() <= 256 ? text : null;
    }

    private Shape finishShapeInspection(Shape shape, DisplayConfig config) {
        if (config != null
                && config.diagnostics
                && auditBudget.accept(shape.commands, shape.textCommands, shape.readFailed))
            logDiagnosticSafely(shape.log());
        return shape;
    }

    private void reportInspectionFailure() {
        try {
            if (configuration.get().diagnostics && failureLogs++ < 8)
                logDiagnosticSafely("balance-shape readFailed=true");
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static void logDiagnosticSafely(String message) {
        try {
            Log.i(TAG, message);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    // 每次绘制与重试持有独立状态，归还时仍按引用核对。

    /** 单次绘制的比较并归还；宿主重入换canvas时保留其新引用。 */
    static final class DrawLease implements AutoCloseable {
        private BalanceCanvasSession session;
        private final View view;
        private final Field canvasField;
        private Object original, owned;
        private Object previousOwned;
        private final boolean blanked;
        private final String blankReason;
        private boolean completed;

        private DrawLease(
                BalanceCanvasSession session,
                View view,
                Field canvasField,
                Object original,
                Object owned,
                Object previousOwned,
                boolean blanked,
                String blankReason) {
            this.session = session;
            this.view = view;
            this.canvasField = canvasField;
            this.original = original;
            this.owned = owned;
            this.previousOwned = previousOwned;
            this.blanked = blanked;
            this.blankReason = blankReason;
        }

        void markDrawCompleted() {
            completed = true;
        }

        @Override
        public void close() {
            if (owned == null) return;
            boolean restored = false, hostChanged = false;
            try {
                if (canvasField.get(view) == owned) {
                    canvasField.set(view, original);
                    restored = canvasField.get(view) == original;
                } else hostChanged = true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            } finally {
                if (session != null)
                    session.finishDraw(
                            view,
                            owned,
                            previousOwned,
                            blanked,
                            blankReason,
                            completed,
                            restored,
                            hostChanged);
                original = null;
                owned = null;
                previousOwned = null;
                session = null;
            }
        }
    }

    /** 只请求重画，不保存源canvas/文字；两秒后继续保护主金额但停止主动重画。 */
    private final class FontRetry implements Runnable {
        private final View view;
        private final long revision;
        private final RetryBudget budget = new RetryBudget(SystemClock.uptimeMillis());
        private boolean pending, cancelled;

        FontRetry(View view, long revision) {
            this.view = view;
            this.revision = revision;
        }

        void schedule() {
            if (pending || cancelled || !budget.claim(SystemClock.uptimeMillis())) return;
            pending = true;
            try {
                if (!view.postDelayed(this, 50)) stop();
            } catch (RuntimeException | LinkageError ignored) {
                stop();
                reportInspectionFailure();
            }
        }

        @Override
        public void run() {
            pending = false;
            try {
                DisplayConfig config = configuration.get();
                if (cancelled
                        || fontRetries.get(view) != this
                        || !owns(view)
                        || !isCurrent()
                        || !view.isShown()
                        || config == null
                        || !config.enabled
                        || config.revision != revision) {
                    cancel();
                    return;
                }
                if (isConfigurationPending()) {
                    stop();
                    return;
                }
                if (budget.expired(SystemClock.uptimeMillis())) return;
                view.invalidate();
            } catch (RuntimeException | LinkageError ignored) {
                stop();
                reportInspectionFailure();
            }
        }

        // 预算耗尽保留同view/revision终态，后续自然draw不能重新开启两秒窗口。
        void stop() {
            budget.stop();
            pending = false;
            try {
                view.removeCallbacks(this);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }

        void cancel() {
            cancelled = true;
            stop();
            if (fontRetries.get(view) == this) fontRetries.remove(view);
        }
    }

    static final class Shape {
        boolean ownerCurrent,
                shown,
                commandMode,
                noPicture,
                noBitmap,
                noDemotion,
                shapeKnown,
                textOrderKnown,
                commandTypesPinned,
                configPending;
        boolean oneLine,
                oneRun,
                runCoversText,
                needDraw,
                typefaceInfoZero,
                styleNormal,
                weightNormal,
                letterSpacingZero;
        boolean noImage,
                noShadow,
                noDecoration,
                noHighlight,
                symbolZero,
                noLineDecoration,
                noComplexStyle,
                noBoxShadow,
                noBackgroundStyles,
                visiblePaint;
        boolean fontAvailable,
                fontCacheStable,
                paintSetupKnown,
                paintLinear,
                paintSubpixel,
                paintFakeBold,
                paintSkewZero,
                paintScaleOne;
        boolean metricsFinite,
                measuredWidthsFinite,
                sourceMatchesLine,
                sourceMatchesRun,
                lineMatchesRun;
        boolean sourceMetricsMatch,
                fitChecked,
                configFits,
                blankProof,
                cloneCandidate,
                metricsPending,
                readFailed;
        int commands,
                textCommands,
                extraTextCommands,
                titleCount,
                amountCount,
                titleIndex = -1,
                amountIndex = -1,
                maskCount,
                lines,
                runs;
        String commandTypesFingerprint = "unavailable";
        String decoration = "unknown";

        String log() {
            return "balance-shape ownerCurrent="
                    + ownerCurrent
                    + " shown="
                    + shown
                    + " commandMode="
                    + commandMode
                    + " configPending="
                    + configPending
                    + " noPicture="
                    + noPicture
                    + " noBitmap="
                    + noBitmap
                    + " noDemotion="
                    + noDemotion
                    + " commands="
                    + commands
                    + " textCommands="
                    + textCommands
                    + " extraTextCommands="
                    + extraTextCommands
                    + " commandTypesFingerprint="
                    + commandTypesFingerprint
                    + " commandTypesPinned="
                    + commandTypesPinned
                    + " titleCount="
                    + titleCount
                    + " amountCount="
                    + amountCount
                    + " titleIndex="
                    + titleIndex
                    + " amountIndex="
                    + amountIndex
                    + " maskCount="
                    + maskCount
                    + " shapeKnown="
                    + shapeKnown
                    + " textOrderKnown="
                    + textOrderKnown
                    + " lines="
                    + lines
                    + " runs="
                    + runs
                    + " oneLine="
                    + oneLine
                    + " oneRun="
                    + oneRun
                    + " runCoversText="
                    + runCoversText
                    + " needDraw="
                    + needDraw
                    + " typefaceInfoZero="
                    + typefaceInfoZero
                    + " styleNormal="
                    + styleNormal
                    + " weightNormal="
                    + weightNormal
                    + " letterSpacingZero="
                    + letterSpacingZero
                    + " noImage="
                    + noImage
                    + " noShadow="
                    + noShadow
                    + " noDecoration="
                    + noDecoration
                    + " decoration="
                    + decoration
                    + " noHighlight="
                    + noHighlight
                    + " symbolZero="
                    + symbolZero
                    + " noLineDecoration="
                    + noLineDecoration
                    + " noBoxShadow="
                    + noBoxShadow
                    + " noBackgroundStyles="
                    + noBackgroundStyles
                    + " visiblePaint="
                    + visiblePaint
                    + " noComplexStyle="
                    + noComplexStyle
                    + " fontAvailable="
                    + fontAvailable
                    + " fontCacheStable="
                    + fontCacheStable
                    + " paintSetupKnown="
                    + paintSetupKnown
                    + " paintLinear="
                    + paintLinear
                    + " paintSubpixel="
                    + paintSubpixel
                    + " paintFakeBold="
                    + paintFakeBold
                    + " paintSkewZero="
                    + paintSkewZero
                    + " paintScaleOne="
                    + paintScaleOne
                    + " metricsFinite="
                    + metricsFinite
                    + " measuredWidthsFinite="
                    + measuredWidthsFinite
                    + " sourceMatchesLine="
                    + sourceMatchesLine
                    + " sourceMatchesRun="
                    + sourceMatchesRun
                    + " lineMatchesRun="
                    + lineMatchesRun
                    + " sourceMetricsMatch="
                    + sourceMetricsMatch
                    + " fitChecked="
                    + fitChecked
                    + " configFits="
                    + configFits
                    + " blankProof="
                    + blankProof
                    + " cloneCandidate="
                    + cloneCandidate
                    + " metricsPending="
                    + metricsPending
                    + " readFailed="
                    + readFailed;
        }
    }

    static final class AuditBudget {
        private int targets57, targets111, failures;
        private final Set<Integer> other = new HashSet<>(), suspects = new HashSet<>();

        boolean accept(int commandCount, int textCount, boolean failed) {
            if (commandCount == 57) return targets57++ < 96;
            if (commandCount == 111) return targets111++ < 96;
            if (failed) return failures++ < 8;
            if (textCount == 4) return suspects.size() < 12 && suspects.add(commandCount);
            return other.size() < 8 && other.add(commandCount);
        }
    }

    static final class RetryBudget {
        private final long started;
        private int attempts;
        private boolean exhausted;

        RetryBudget(long started) {
            this.started = started;
        }

        boolean claim(long now) {
            if (exhausted) return false;
            if (!Policy.retryAllowed(now - started, attempts)) {
                exhausted = true;
                return false;
            }
            attempts++;
            return true;
        }

        boolean expired(long now) {
            if (now - started < 0 || now - started > 2000) exhausted = true;
            return exhausted;
        }

        void stop() {
            exhausted = true;
        }
    }

    static final class Policy {
        static boolean protectPending(boolean pending, boolean blankProof) {
            return pending && blankProof;
        }

        static boolean shouldBlank(boolean blankProof, boolean cloneCandidate, boolean pending) {
            return blankProof && (pending || !cloneCandidate);
        }

        static String blankReason(
                boolean pending,
                boolean blank,
                boolean fontAvailable,
                boolean fontStable,
                boolean fitChecked,
                boolean fits) {
            if (!blank) return "none";
            if (pending) return "config";
            if (fitChecked && !fits) return "fit";
            return !fontAvailable || !fontStable ? "font" : "metrics";
        }

        static boolean retryForBlank(
                boolean blankProof,
                boolean fontAvailable,
                boolean fontStable,
                boolean fitChecked,
                boolean fits,
                boolean metricsPending) {
            return blankProof
                    && !(fitChecked && !fits)
                    && (!fontAvailable || !fontStable || metricsPending);
        }

        static boolean suppressKnown(
                boolean enabled,
                boolean pending,
                boolean ownerCurrent,
                boolean shapeKnown,
                boolean sameSlot,
                boolean ownReentry) {
            return (enabled || pending) && ownerCurrent && shapeKnown && sameSlot && !ownReentry;
        }

        static boolean fenceShape(
                boolean shapeKnown,
                boolean commandMode,
                boolean noPicture,
                boolean noBitmap,
                boolean noDemotion) {
            return shapeKnown && commandMode && noPicture && noBitmap && noDemotion;
        }

        static boolean drawProofCurrent(
                long capturedRevision,
                long currentRevision,
                boolean capturedPending,
                boolean currentPending,
                boolean enabled) {
            return capturedRevision == currentRevision
                    && pendingProofCurrent(capturedPending, currentPending, enabled);
        }

        static boolean pendingProofCurrent(
                boolean capturedPending, boolean currentPending, boolean enabled) {
            // 新读取开始时，尚未发布的新配置不使同revision已验证副本失效。
            return capturedPending ? currentPending : enabled;
        }

        static boolean pinnedSequence(String fingerprint) {
            return pinnedSequence(57, fingerprint);
        }

        static boolean pinnedSequence(int commands, String fingerprint) {
            return commands == 57
                            && "f1c7cf5b167cc3d2ad3fa98840f90380460cfae3830a0e1b16c64f374ee30588"
                                    .equals(fingerprint)
                    || commands == 111
                            && "4583e698c6aa1a0f2412a4ced8a370ef45f2c578113f849eda688023e8456100"
                                    .equals(fingerprint);
        }

        static boolean textOrderKnown(int commands, int[] order, int count) {
            int[] expected;
            if (commands == 57) expected = new int[] {11, 27, 45, 51};
            else if (commands == 111) expected = new int[] {11, 27, 45, 51, 68, 77, 82, 86};
            else return false;
            if (order == null || count != expected.length || order.length < count) return false;
            for (int index = 0; index < count; index++)
                if (order[index] != expected[index]) return false;
            return true;
        }

        static String decoration(int value) {
            return switch (value) {
                case 1 -> "none";
                case 2 -> "underline";
                case 4 -> "line-through";
                case 8 -> "overline";
                default -> "other";
            };
        }

        static boolean shape(
                int commands,
                int labels,
                int amounts,
                int labelIndex,
                int amountIndex,
                boolean textOrder,
                int masks) {
            return (commands == 57 || commands == 111)
                    && labels == 1
                    && amounts == 1
                    && labelIndex == 11
                    && amountIndex == 27
                    && textOrder
                    && masks == 0;
        }

        static boolean metrics(
                float textSize, float contentWidth, float x, float lineWidth, float runWidth) {
            return Float.isFinite(textSize)
                    && textSize > 0
                    && Float.isFinite(contentWidth)
                    && contentWidth > 0
                    && Float.isFinite(x)
                    && x >= 0
                    && Float.isFinite(lineWidth)
                    && lineWidth > 0
                    && Float.isFinite(runWidth)
                    && runWidth > 0;
        }

        static boolean fits(float x, float width, float available) {
            return Float.isFinite(x)
                    && Float.isFinite(width)
                    && Float.isFinite(available)
                    && x >= 0
                    && width > 0
                    && available > 0
                    && x + width <= available;
        }

        static boolean measuredWidths(float source, float configured) {
            return Float.isFinite(source)
                    && source > 0
                    && Float.isFinite(configured)
                    && configured > 0;
        }

        static boolean widthMatches(float measured, float expected) {
            return Float.isFinite(measured)
                    && measured > 0
                    && Float.isFinite(expected)
                    && expected > 0
                    && Math.abs(measured - expected) <= 1.5f;
        }

        static boolean metricsPending(boolean allOtherGates, boolean sourceMatches) {
            return allOtherGates && !sourceMatches;
        }

        static boolean retryAllowed(long elapsedMillis, int attempts) {
            return elapsedMillis >= 0 && elapsedMillis < 2000 && attempts >= 0 && attempts < 40;
        }

        static boolean positiveAlpha(float value) {
            return Float.isFinite(value) && value > 0 && value <= 1;
        }

        static boolean canvasSize(double width, double height) {
            return Double.isFinite(width)
                    && Double.isFinite(height)
                    && width > 0
                    && height > 0
                    && width <= 16384
                    && height <= 16384;
        }

        static boolean sameEntries(Object[] before, Object[] after) {
            if (before == null || after == null || before.length != after.length) return false;
            for (int index = 0; index < before.length; index++)
                if (before[index] != after[index]) return false;
            return true;
        }

        static boolean onlyTargetChanged(Object[] before, Object[] after, int target) {
            if (before == null
                    || after == null
                    || before.length != after.length
                    || target < 0
                    || target >= before.length) return false;
            for (int index = 0; index < before.length; index++)
                if ((before[index] == after[index]) == (index == target)) return false;
            return true;
        }

        static boolean ownedReentry(Object current, Object owned) {
            return owned != null && current == owned;
        }

        static String classSequenceFingerprint(String[] names) {
            if (names == null || names.length != 57 && names.length != 111) return "unavailable";
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
