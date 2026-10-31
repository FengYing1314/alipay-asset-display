package io.github.fengying.alipaydisplay;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 默认关闭的只读结构探针；原始金额、URL、账户文案均不进入回调或日志。 */
final class PageDiagnostics {
    private static final String TAG = "AlipayDisplayDiag";
    private static final String WEB_VIEW = "com.alipay.mywebview.sdk.WebView";
    private static final int MAX_VIEWS = 1024;
    private static final int MAX_ROUNDS = 3;
    private static final int MAX_WEB_VIEWS = 2;
    private static final int MAX_DOM_NODES = 200;
    private static final int MAX_PROBE_CHARS = 384 * 1024;
    private static final long[] SAMPLE_DELAYS = {400L, 1500L, 3000L};

    // 仅返回固定结构字段和布尔状态，不输出DOM文本、任意class/id、路由或属性值。
    private static final String PROBE =
            """
            (() => {
              const known = ['overview-card','asset-card','amountView','total-text-container',
                'total-text-container-big','assets-all','total-asset','wu-number',
                'wu-alisans-number','asset-text'];
              const labels = ['总资产','余额','余额宝','基金','总金额','总金额(元)',
                '总金额（元）','总资产(元)','总资产（元）','显示','隐藏'];
              const tags = ['DIV','SPAN','BUTTON','P','SECTION','MAIN','A','IMG','TEXT','VIEW','LABEL'];
              const roles = ['button','text','group','heading','img','none'];
              const selectors = known.flatMap(k => ['[id="' + k + '"]', '[class~="' + k + '"]']);
              const rawRoots = Array.from(document.querySelectorAll(selectors.join(',')));
              const hasToken = (e,k) => e.id === k || e.classList.contains(k);
              const structureNames = ['overview-card','asset-card','assets-all',
                'total-text-container','total-text-container-big'];
              const structureRoots = rawRoots.filter(e => structureNames.some(k => hasToken(e,k)));
              const fundRoots = rawRoots.filter(e => hasToken(e,'assets-all'));
              const allRoots = rawRoots.filter(e => {
                const number = hasToken(e,'wu-number') || hasToken(e,'wu-alisans-number');
                if (structureRoots.length && number && !structureRoots.some(r => r === e || r.contains(e))) return false;
                return !fundRoots.length || !hasToken(e,'total-asset') || fundRoots.some(r => r.contains(e));
              });
              const fundTargets = fundRoots.length === 1 ? rawRoots.filter(e =>
                hasToken(e,'total-asset') && e !== fundRoots[0] && fundRoots[0].contains(e)) : [];
              const fundTarget = fundTargets.length === 1 ? fundTargets[0] : null;
              const roots = allRoots.filter(e => !allRoots.some(a => a !== e && a.contains(e))).slice(0,16);
              const result = []; let depthTruncated = false, nodeTruncated = false;
              const rootTruncated = allRoots.filter(e => !allRoots.some(a => a !== e && a.contains(e))).length > 16;
              const semantic = text => {
                const flags = [text.includes('总资产'),text.includes('余额宝'),text.includes('基金'),
                  text.replace(/余额宝/g,'').includes('余额'),text.includes('总金额')];
                return flags.filter(Boolean).length === 1 ?
                  ['total-assets','yuebao','fund','balance','total-amount'][flags.indexOf(true)] : 'none';
              };
              const numeric = text => /^(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?$/
                .test(text.replace(/[\\s\\u200B-\\u200D\\uFEFF]/g,''));
              const canonicalNumber = text => {
                if (!numeric(text)) return null;
                const parts = text.replace(/[\\s\\u200B-\\u200D\\uFEFF,]/g,'').split('.');
                return parts[0].replace(/^0+(?=[0-9])/,'') + '.' + (parts[1] || '').padEnd(2,'0');
              };
              const fundDetails = (e,aria,subtree) => {
                if (!fundTarget || !(e === fundTarget || fundTarget.contains(e))) return {};
                const compact = text => text.replace(/[\\s\\u200B-\\u200D\\uFEFF]/g,'');
                const wholeAria = compact(aria).replace(/元$/,'');
                const canonicalAria = canonicalNumber(wholeAria);
                const canonicalSubtree = canonicalNumber(subtree);
                let splitTextRole = 'OTHER';
                const parent = e.parentElement;
                if (e.tagName === 'SPAN' && e.children.length === 0 && parent &&
                    parent.getAttribute('data-mark') === 'totalAsset' &&
                    (hasToken(parent,'wu-number') || hasToken(parent,'wu-alisans-number'))) {
                  const value = compact(e.textContent || '');
                  const siblings = Array.from(parent.children);
                  const point = siblings.findIndex(s => compact(s.textContent || '') === '.');
                  if (value === ',') splitTextRole = 'COMMA';
                  else if (value === '.') splitTextRole = 'POINT';
                  else if (/^[0-9]+$/.test(value)) splitTextRole = point >= 0 && siblings.indexOf(e) > point ? 'FRACTION' : 'INTEGER';
                }
                return {fundSummaryNode:true,dataMarkTotalAsset:e.getAttribute('data-mark') === 'totalAsset',
                  splitTextRole,ariaWholeNumericOrYuan:canonicalAria !== null,
                  ariaWholeMatchesSubtreeNumber:canonicalAria !== null && canonicalSubtree !== null && canonicalAria === canonicalSubtree};
              };
              const ariaBoundary = (e,aria,kind) => {
                const labels = {'total-assets':'总资产',balance:'余额',yuebao:'余额宝',fund:'基金','total-amount':'总金额'};
                const indexes = kind === 'total-assets' ? [0,0] : kind === 'total-amount' ? [0] : [1,0,0];
                let main = Object.prototype.hasOwnProperty.call(labels,kind) ? e : null;
                for (const index of indexes) main = main && main.children[index];
                const mainText = main ? main.textContent || '' : '';
                const firstPattern = /[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?/g;
                const input = aria.slice(0,4096);
                let count = 0, first = null, firstStrict = false, match;
                while ((match = firstPattern.exec(input)) !== null && count < 33) {
                  if (count++ === 0) {
                    first = canonicalNumber(match[0]);
                    const before = match.index > 0 ? input[match.index-1] : '';
                    const end = match.index+match[0].length;
                    const after = input[end] || '';
                    firstStrict = first !== null && !/[A-Za-z0-9.]/.test(before+after) &&
                      !(after === ',' && /[0-9]/.test(input[end+1] || ''));
                  }
                }
                const mainValue = canonicalNumber(mainText);
                const starts = !!labels[kind] && aria.trim().startsWith(labels[kind]) &&
                  !(kind === 'balance' && aria.trim().startsWith('余额宝'));
                return {ariaMainRelativePath:labels[kind]?indexes.join('.'):'none',
                  ariaMainExists:!!main,ariaMainLeaf:!!main && main.children.length === 0,
                  ariaMainNumericOnly:mainValue !== null,ariaStartsKindLabel:starts,
                  ariaPureNumeric:numeric(aria),ariaStartsTotalAmountPrefix:aria.trim().startsWith('总金额'),
                  ariaNumberTokenCount:Math.min(count,32),ariaTokensTruncated:aria.length>4096 || count>32,
                  ariaFirstTokenStrict:firstStrict,
                  ariaFirstMatchesMain:mainValue !== null && first !== null && mainValue === first};
              };
              const state = e => {
                const style = getComputedStyle(e);
                return {hidden:e.hidden || style.display === 'none' || style.visibility === 'hidden' ||
                  style.visibility === 'collapse' || style.opacity === '0',
                  ariaHidden:e.getAttribute('aria-hidden') === 'true'};
              };
              const ancestorState = e => {
                let hidden = false, ariaHidden = false, count = 0;
                for (let p = e.parentElement; p; p = p.parentElement) {
                  if (++count > 64) return {hidden:true,ariaHidden:true,truncated:true};
                  const s = state(p); hidden ||= s.hidden; ariaHidden ||= s.ariaHidden;
                }
                return {hidden,ariaHidden,truncated:false};
              };
              const queue = roots.map((e,i) => [e,String(i),0,ancestorState(e),
                known.find(k => e.id === k || e.classList.contains(k)) || '', '']);
              const anchors = [], anchorCounts = {balance:0,yuebao:0,fund:0};
              const amountSeeds = new Set();
              let nextRoot = roots.length, semanticRoots = 0, amountAncestorRoots = 0;
              let ancestorTruncated = false, amountAncestorTruncated = false;
              while (queue.length || anchors.length) {
                if (result.length >= 200) { nodeTruncated = true; break; }
                const [e,path,depth,ancestor,rootToken,anchorOf] = anchors.length ? anchors.shift() : queue.shift();
                const isSemanticAnchor = rootToken.startsWith('semantic-');
                const isAmountAnchor = rootToken.startsWith('amount-parent-');
                const isAnchor = isSemanticAnchor || isAmountAnchor;
                if (isSemanticAnchor && depth === 0) semanticRoots++;
                if (isAmountAnchor && depth === 0) amountAncestorRoots++;
                const text = Array.from(e.childNodes).filter(n => n.nodeType === 3)
                  .map(n => n.nodeValue || '').join('').trim();
                const subtree = e.textContent || '';
                const aria = e.getAttribute('aria-label') || '';
                const token = known.filter(k => e.id === k || e.classList.contains(k));
                const label = labels.includes(text) ? text : '';
                const role = e.getAttribute('role') || '';
                const own = state(e);
                const effectiveHidden = ancestor.hidden || own.hidden;
                const effectiveAriaHidden = ancestor.ariaHidden || own.ariaHidden;
                const shown = !effectiveHidden && e.isConnected !== false && e.getClientRects().length > 0;
                const ariaSemantic = semantic(aria);
                const hideOrMask = /[*＊●•]{2,}|隐藏|不可见|不显示/.test(aria+' '+subtree);
                const rowSemantic = Object.prototype.hasOwnProperty.call(anchorCounts,ariaSemantic) ? ariaSemantic :
                  hideOrMask && aria.trim() ? semantic(subtree) : 'none';
                const boundaryKind = token.includes('amountView') ? 'total-amount' :
                  rootToken === 'overview-card' && ariaSemantic === 'total-assets' ? 'total-assets' :
                  (rootToken === 'asset-card' || isAnchor) && Object.prototype.hasOwnProperty.call(anchorCounts,rowSemantic) ?
                    rowSemantic : 'none';
                const boundary = ariaBoundary(e,aria,boundaryKind);
                ancestorTruncated ||= ancestor.truncated;
                result.push({path,depth,rootToken,anchorOf,tag:tags.includes(e.tagName)?e.tagName:'OTHER',token,label,
                  ...boundary,
                  ...fundDetails(e,aria,subtree),
                  subtreeLabel:labels.includes(subtree.trim())?subtree.trim():'',
                  role:roles.includes(role)?role:'', textDigits:/[0-9]/.test(text),
                  textMask:/[*＊●•]{2,}/.test(text), ariaDigits:/[0-9]/.test(aria),
                  ariaMask:/[*＊●•]{2,}/.test(aria),
                  subtreeDigits:/[0-9]/.test(subtree),subtreeMask:/[*＊●•]{2,}/.test(subtree),
                  directTextNumericOnly:numeric(text),subtreeTextNumericOnly:numeric(subtree),
                  textSemantic:semantic(text),subtreeSemantic:semantic(subtree),ariaSemantic,
                  availableAmountLabel:/^可用金额(?:[（(]元[）)])?$/.test(text),
                  textHidden:/隐藏|不可见|不显示/.test(text), ariaHiddenText:/隐藏|不可见|不显示/.test(aria),
                  hidden:own.hidden,ancestorHidden:ancestor.hidden,effectiveHidden,
                  ariaHidden:own.ariaHidden,ancestorAriaHidden:ancestor.ariaHidden,effectiveAriaHidden,
                  shown,
                  children:Math.min(e.children.length,200)});
                if (!isAnchor && rootToken === 'asset-card' &&
                    Object.prototype.hasOwnProperty.call(anchorCounts,rowSemantic) && anchorCounts[rowSemantic] < 3 &&
                    shown && ((/[0-9]/.test(aria) && !effectiveAriaHidden) || hideOrMask)) {
                  if (result.length + queue.length + anchors.length < 200) {
                    anchorCounts[rowSemantic]++;
                    anchors.push([e,String(nextRoot++),0,ancestorState(e),'semantic-'+rowSemantic,path]);
                  } else nodeTruncated = true;
                }
                if (!isAnchor && token.includes('amountView') && !amountSeeds.has(e)) {
                  if (amountSeeds.size < 3) {
                    amountSeeds.add(e);
                    let parent = e.parentElement;
                    for (let level=1; level<=3 && parent; level++,parent=parent.parentElement) {
                      if (parent.tagName === 'BODY' || parent.tagName === 'HTML') {
                        amountAncestorTruncated = true; break;
                      }
                      if (result.length + queue.length + anchors.length >= 200) {
                        nodeTruncated = true; break;
                      }
                      anchors.push([parent,String(nextRoot++),0,ancestorState(parent),'amount-parent-'+level,path]);
                    }
                  } else amountAncestorTruncated = true;
                }
                const maxDepth = isAnchor ? 6 : 8;
                if (depth < maxDepth) Array.from(e.children).forEach((c,i) => {
                  if (result.length + queue.length + anchors.length >= 200) { nodeTruncated = true; return; }
                  const target = isAnchor ? anchors : queue;
                  target.push([c,path+'.'+i,depth+1,{hidden:effectiveHidden,
                    ariaHidden:effectiveAriaHidden,truncated:ancestor.truncated},rootToken,anchorOf]);
                });
                if (depth === maxDepth && e.children.length) depthTruncated = true;
              }
              const amountScopes = Array.from(document.querySelectorAll('#amountView'));
              const amountScope = amountScopes.length === 1 ? amountScopes[0] : null;
              const amountParent = amountScope && amountScope.parentElement;
              const amountTitle = amountParent && amountParent.children[0];
              const amountCaption = amountTitle && amountTitle.children[0];
              const amountSpan = amountScope && amountScope.children[0];
              const amountIcon = amountScope && amountScope.children[1];
              const singleText = e => !!e && e.childNodes.length === 1 && e.firstChild.nodeType === 3;
              const amountShape = amountScope ? {
                captionSingleText:singleText(amountCaption),
                captionExact:!!amountCaption && amountCaption.textContent === '总金额(元)',
                captionTrimmedExact:!!amountCaption && amountCaption.textContent.trim() === '总金额(元)',
                titleAriaExact:!!amountTitle && amountTitle.getAttribute('aria-label') === '总金额(元)',
                amountSingleText:singleText(amountSpan),
                iconSvg:!!amountIcon && String(amountIcon.tagName).toUpperCase() === 'SVG'
              } : null;
              let fundLayout = null;
              if (fundTarget) {
                const candidates = Array.from(fundTarget.children).filter(e =>
                  e.tagName === 'SPAN' && hasToken(e,'wu-number') && hasToken(e,'wu-alisans-number')
                  && e.getAttribute('data-mark') === null && e.getAttribute('aria-hidden') === 'true');
                if (candidates.length === 1) {
                  const candidate = candidates[0], range = document.createRange();
                  range.selectNodeContents(candidate);
                  const rects = Array.from(range.getClientRects()).filter(r => r.width > 0 && r.height > 0);
                  const inside = (r,b) => r.left >= b.left-1 && r.top >= b.top-1 && r.right <= b.right+1 && r.bottom <= b.bottom+1;
                  let clipping = true, depth = 0, ancestor = candidate.parentElement;
                  for (; ancestor && depth < 32; ancestor = ancestor.parentElement, depth++) {
                    const css = getComputedStyle(ancestor), b = ancestor.getBoundingClientRect();
                    if (css.clipPath && css.clipPath !== 'none') clipping = false;
                    for (const r of rects) {
                      if (css.overflowX !== 'visible' && (r.left < b.left-1 || r.right > b.right+1)) clipping = false;
                      if (css.overflowY !== 'visible' && (r.top < b.top-1 || r.bottom > b.bottom+1)) clipping = false;
                    }
                  }
                  fundLayout = {connected:candidate.isConnected,rectPositive:rects.length > 0,
                    rangeSingleLine:rects.length === 1,
                    insideScope:rects.length > 0 && rects.every(r => inside(r,fundTarget.getBoundingClientRect())),
                    insideViewport:rects.length > 0 && rects.every(r => inside(r,{left:0,top:0,
                      right:document.documentElement.clientWidth,bottom:document.documentElement.clientHeight})),
                    insideClippingAncestors:rects.length > 0 && clipping && !ancestor};
                }
              }
              return JSON.stringify({schema:8,ready:document.readyState === 'complete',amountShape,fundLayout,
                roots:roots.length+semanticRoots+amountAncestorRoots,domRoots:roots.length,semanticRoots,amountAncestorRoots,
                truncated:depthTruncated||nodeTruncated||rootTruncated||ancestorTruncated||amountAncestorTruncated,
                depthTruncated,nodeTruncated,rootTruncated,ancestorTruncated,amountAncestorTruncated,nodes:result});
            })()
            """;

    private final Supplier<DisplayConfig> config;
    private final Consumer<Class<?>> observer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DiagnosticPolicy.Epoch epoch = new DiagnosticPolicy.Epoch();
    private final Map<View, Object> callbacks = new IdentityHashMap<>();
    private final Runnable inspectionTask = () -> runDiagnosticSafely(this::inspectPage);
    private Activity activity;
    private int rounds;
    private boolean queued;
    private boolean closed;

    PageDiagnostics(Supplier<DisplayConfig> config) {
        this(config, ignored -> {});
    }

    PageDiagnostics(Supplier<DisplayConfig> config, Consumer<Class<?>> observer) {
        this.config = config;
        this.observer = observer;
    }

    void onResume(Activity value) {
        if (closed || value == null) return;
        cancelPendingProbes();
        activity = value;
        rounds = 0;
        epoch.resume();
        scheduleInspection();
    }

    void onPause(Activity value) {
        if (activity != value) return;
        cancelPendingProbes();
        epoch.pause();
        activity = null;
    }

    void onStructureChanged(View value) {
        if (activity == null || value == null || value.getRootView() != decorRoot()) return;
        scheduleInspection();
    }

    void onDocumentChanged(View value) {
        if (activity == null || value == null || value.getRootView() != decorRoot()) return;
        cancelPendingProbes();
        epoch.invalidate();
        rounds = 0;
        scheduleInspection();
    }

    void close() {
        closed = true;
        cancelPendingProbes();
        epoch.pause();
        activity = null;
    }

    // 每轮采样共用同一代次，取消与页面变化必须同时撤销待处理回调。

    private void scheduleInspection() {
        if (!diagnosticsEnabled()) {
            cancelPendingProbes();
            epoch.invalidate();
            return;
        }
        if (queued || rounds >= MAX_ROUNDS) return;
        queued = true;
        main.postDelayed(inspectionTask, SAMPLE_DELAYS[rounds]);
    }

    private void cancelPendingProbes() {
        main.removeCallbacks(inspectionTask);
        queued = false;
        callbacks.clear();
    }

    private void inspectPage() {
        queued = false;
        if (!diagnosticsEnabled() || rounds++ >= MAX_ROUNDS) return;
        View root = decorRoot();
        if (root == null || !root.isAttachedToWindow()) {
            scheduleInspection();
            return;
        }
        Identity identity = capturePageIdentity();
        String page = identity.page;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int webCount = 0;
        boolean cube = false;
        boolean cubeNg = false;
        boolean canvas = false;
        boolean truncated = false;
        long pageEpoch = epoch.page();
        long request = epoch.request();
        while (!queue.isEmpty() && visited++ < MAX_VIEWS) {
            View view = queue.remove();
            String type = view.getClass().getName();
            Class<?> webClass = findWebViewClass(view);
            if (webClass != null && webCount++ < MAX_WEB_VIEWS) {
                try {
                    observer.accept(webClass);
                } catch (RuntimeException ignored) {
                    /* 诊断不影响宿主页面。 */
                }
                if (identity.hasReliableOwner())
                    evaluateStructureProbe(view, identity, pageEpoch, request);
                else Log.i(TAG, "page=" + page + " webProbe=identity-pending");
            }
            if ("com.alipay.mobile.antcube.renderng.view.MPCubeView".equals(type)
                    || "com.alipay.mobile.antcube.renderng.view.AntCubeView".equals(type)
                    || "com.antfin.cube.cubecore.api.CubeView".equals(type)) cube = true;
            if ("com.antfin.cube.cubebridge.view.CubeViewNgImpl".equals(type)) cubeNg = true;
            if ("com.antfin.cube.cubecore.component.widget.CKPView".equals(type)) canvas = true;
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (visited + queue.size() < MAX_VIEWS) queue.add(group.getChildAt(i));
                    else truncated = true;
                }
            }
        }
        Log.i(
                TAG,
                identity.diagnosticMetadata()
                        + " web="
                        + Math.min(webCount, 3)
                        + " cube="
                        + cube
                        + " cubeNg="
                        + cubeNg
                        + " canvas="
                        + canvas
                        + " truncated="
                        + (truncated || !queue.isEmpty())
                        + " support=observation-only");
        scheduleInspection();
    }

    private void evaluateStructureProbe(
            View view, Identity identity, long pageEpoch, long request) {
        try {
            ClassLoader loader = view.getClass().getClassLoader();
            Class<?> callbackType =
                    Class.forName("com.alipay.mywebview.sdk.ValueCallback", false, loader);
            Method evaluate =
                    view.getClass().getMethod("evaluateJavascript", String.class, callbackType);
            Object callback =
                    Proxy.newProxyInstance(
                            loader,
                            new Class<?>[] {callbackType},
                            (proxy, method, args) -> {
                                if (method.getDeclaringClass() == Object.class) {
                                    if ("hashCode".equals(method.getName()))
                                        return System.identityHashCode(proxy);
                                    if ("equals".equals(method.getName()))
                                        return args != null && args.length == 1 && proxy == args[0];
                                    return "StructureProbeCallback";
                                }
                                if ("onReceiveValue".equals(method.getName())) {
                                    Object result =
                                            args != null && args.length == 1 ? args[0] : null;
                                    try {
                                        main.post(
                                                () ->
                                                        runDiagnosticSafely(
                                                                () ->
                                                                        handleProbeResult(
                                                                                view, proxy, result,
                                                                                identity, pageEpoch,
                                                                                request)));
                                    } catch (RuntimeException | LinkageError ignored) {
                                        // Handler无法接收任务时放弃本次观测，不向宿主抛出异常。
                                    }
                                }
                                return null;
                            });
            callbacks.put(view, callback);
            evaluate.invoke(view, PROBE, callback);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            callbacks.remove(view);
            Log.i(TAG, "page=" + identity.page + " webProbe=unavailable");
        }
    }

    private void handleProbeResult(
            View view,
            Object callback,
            Object raw,
            Identity expected,
            long pageEpoch,
            long request) {
        if (callbacks.get(view) != callback) return;
        callbacks.remove(view);
        if (!diagnosticsEnabled()
                || !epoch.accepts(pageEpoch, request)
                || view.getRootView() != decorRoot()
                || !view.isAttachedToWindow()
                || !expected.matches(capturePageIdentity())) return;
        String page = expected.page;
        try {
            if (!(raw instanceof String text) || text.length() > MAX_PROBE_CHARS)
                throw new IllegalArgumentException();
            Object parsed = new JSONTokener(text).nextValue();
            if (parsed instanceof String inner) parsed = new JSONTokener(inner).nextValue();
            if (!(parsed instanceof JSONObject data) || data.optInt("schema") != 8)
                throw new IllegalArgumentException();
            JSONArray nodes = data.optJSONArray("nodes");
            if (nodes == null || nodes.length() > MAX_DOM_NODES)
                throw new IllegalArgumentException();
            Log.i(
                    TAG,
                    expected.diagnosticMetadata()
                            + " sample="
                            + request
                            + " webProbe=structure schema=8 ready="
                            + data.optBoolean("ready")
                            + " roots="
                            + clampCount(data.optInt("roots"), 34)
                            + " domRoots="
                            + clampCount(data.optInt("domRoots"), 16)
                            + " semanticRoots="
                            + clampCount(data.optInt("semanticRoots"), 9)
                            + " amountAncestorRoots="
                            + clampCount(data.optInt("amountAncestorRoots"), 9)
                            + " nodes="
                            + nodes.length()
                            + " depthLimit=8 anchorDepthLimit=6 nodeLimit="
                            + MAX_DOM_NODES
                            + " truncated="
                            + data.optBoolean("truncated")
                            + " depthTruncated="
                            + data.optBoolean("depthTruncated")
                            + " nodeTruncated="
                            + data.optBoolean("nodeTruncated")
                            + " rootTruncated="
                            + data.optBoolean("rootTruncated")
                            + " ancestorTruncated="
                            + data.optBoolean("ancestorTruncated")
                            + " amountAncestorTruncated="
                            + data.optBoolean("amountAncestorTruncated"));
            JSONObject amountShape = data.optJSONObject("amountShape");
            if (amountShape != null) {
                Log.i(
                        TAG,
                        "sample="
                                + request
                                + " amountShape=true"
                                + " captionSingleText="
                                + amountShape.optBoolean("captionSingleText")
                                + " captionExact="
                                + amountShape.optBoolean("captionExact")
                                + " captionTrimmedExact="
                                + amountShape.optBoolean("captionTrimmedExact")
                                + " titleAriaExact="
                                + amountShape.optBoolean("titleAriaExact")
                                + " amountSingleText="
                                + amountShape.optBoolean("amountSingleText")
                                + " iconSvg="
                                + amountShape.optBoolean("iconSvg"));
            }
            JSONObject fundLayout = data.optJSONObject("fundLayout");
            if (fundLayout != null) {
                Log.i(
                        TAG,
                        "sample="
                                + request
                                + " fundLayout=true connected="
                                + fundLayout.optBoolean("connected")
                                + " rectPositive="
                                + fundLayout.optBoolean("rectPositive")
                                + " rangeSingleLine="
                                + fundLayout.optBoolean("rangeSingleLine")
                                + " insideScope="
                                + fundLayout.optBoolean("insideScope")
                                + " insideViewport="
                                + fundLayout.optBoolean("insideViewport")
                                + " insideClippingAncestors="
                                + fundLayout.optBoolean("insideClippingAncestors"));
            }
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                String path = DiagnosticPolicy.nodePath(node.optString("path"));
                if (path.isEmpty()) continue;
                int separator = path.lastIndexOf('.');
                int index = Integer.parseInt(separator < 0 ? path : path.substring(separator + 1));
                String rootToken = DiagnosticPolicy.rootToken(node.optString("rootToken"));
                String anchorOf = DiagnosticPolicy.nodePath(node.optString("anchorOf"));
                StringBuilder tokens = new StringBuilder();
                JSONArray sourceTokens = node.optJSONArray("token");
                if (sourceTokens != null) {
                    for (int j = 0; j < Math.min(sourceTokens.length(), 10); j++) {
                        String token = DiagnosticPolicy.token(sourceTokens.optString(j));
                        if (!token.isEmpty()) tokens.append(token).append(',');
                    }
                }
                String fundFields =
                        node.optBoolean("fundSummaryNode")
                                ? " fundSummaryNode=true dataMarkTotalAsset="
                                        + node.optBoolean("dataMarkTotalAsset")
                                        + " splitTextRole="
                                        + DiagnosticPolicy.splitTextRole(
                                                node.optString("splitTextRole"))
                                        + " ariaWholeNumericOrYuan="
                                        + node.optBoolean("ariaWholeNumericOrYuan")
                                        + " ariaWholeMatchesSubtreeNumber="
                                        + node.optBoolean("ariaWholeMatchesSubtreeNumber")
                                : "";
                Log.i(
                        TAG,
                        "node="
                                + path
                                + " sample="
                                + request
                                + " parent="
                                + DiagnosticPolicy.parentPath(path)
                                + " ancestors="
                                + DiagnosticPolicy.ancestorPaths(path)
                                + " index="
                                + index
                                + " depth="
                                + clampCount(node.optInt("depth"), 8)
                                + " rootToken="
                                + rootToken
                                + " rootSource="
                                + DiagnosticPolicy.rootSource(rootToken)
                                + " anchorOf="
                                + (anchorOf.isEmpty() ? "none" : anchorOf)
                                + " tag="
                                + DiagnosticPolicy.tag(node.optString("tag"))
                                + " token="
                                + tokens
                                + " label="
                                + DiagnosticPolicy.label(node.optString("label"))
                                + " subtreeLabel="
                                + DiagnosticPolicy.label(node.optString("subtreeLabel"))
                                + " role="
                                + DiagnosticPolicy.role(node.optString("role"))
                                + " textDigits="
                                + node.optBoolean("textDigits")
                                + " textMask="
                                + node.optBoolean("textMask")
                                + " subtreeDigits="
                                + node.optBoolean("subtreeDigits")
                                + " subtreeMask="
                                + node.optBoolean("subtreeMask")
                                + " directTextNumericOnly="
                                + node.optBoolean("directTextNumericOnly")
                                + " subtreeTextNumericOnly="
                                + node.optBoolean("subtreeTextNumericOnly")
                                + " textSemantic="
                                + DiagnosticPolicy.semantic(node.optString("textSemantic"))
                                + " subtreeSemantic="
                                + DiagnosticPolicy.semantic(node.optString("subtreeSemantic"))
                                + " ariaSemantic="
                                + DiagnosticPolicy.semantic(node.optString("ariaSemantic"))
                                + " ariaMainRelativePath="
                                + mainRelativeNodePath(node.optString("ariaMainRelativePath"))
                                + " ariaMainExists="
                                + node.optBoolean("ariaMainExists")
                                + " ariaMainLeaf="
                                + node.optBoolean("ariaMainLeaf")
                                + " ariaMainNumericOnly="
                                + node.optBoolean("ariaMainNumericOnly")
                                + " ariaStartsKindLabel="
                                + node.optBoolean("ariaStartsKindLabel")
                                + " ariaPureNumeric="
                                + node.optBoolean("ariaPureNumeric")
                                + " ariaStartsTotalAmountPrefix="
                                + node.optBoolean("ariaStartsTotalAmountPrefix")
                                + " ariaNumberTokenCount="
                                + clampCount(node.optInt("ariaNumberTokenCount"), 32)
                                + " ariaTokensTruncated="
                                + node.optBoolean("ariaTokensTruncated")
                                + " ariaFirstTokenStrict="
                                + node.optBoolean("ariaFirstTokenStrict")
                                + " ariaFirstMatchesMain="
                                + node.optBoolean("ariaFirstMatchesMain")
                                + " availableAmountLabel="
                                + node.optBoolean("availableAmountLabel")
                                + " ariaDigits="
                                + node.optBoolean("ariaDigits")
                                + " ariaMask="
                                + node.optBoolean("ariaMask")
                                + " textHidden="
                                + node.optBoolean("textHidden")
                                + " ariaHiddenText="
                                + node.optBoolean("ariaHiddenText")
                                + " hidden="
                                + node.optBoolean("hidden")
                                + " ancestorHidden="
                                + node.optBoolean("ancestorHidden")
                                + " effectiveHidden="
                                + node.optBoolean("effectiveHidden")
                                + " ariaHidden="
                                + node.optBoolean("ariaHidden")
                                + " ancestorAriaHidden="
                                + node.optBoolean("ancestorAriaHidden")
                                + " effectiveAriaHidden="
                                + node.optBoolean("effectiveAriaHidden")
                                + " shown="
                                + node.optBoolean("shown")
                                + " children="
                                + clampCount(node.optInt("children"), 200)
                                + fundFields);
            }
        } catch (Exception ignored) {
            Log.i(TAG, "page=" + page + " webProbe=invalid-structure");
        }
    }

    // 身份采集只读取已存在的宿主对象，不触发资源上下文创建。

    private Identity capturePageIdentity() {
        try {
            Object app = activity.getClass().getMethod("getCurrentApp").invoke(activity);
            if (app == null) return Identity.unknown();
            Object id = app.getClass().getMethod("getAppId").invoke(app);
            Object version = app.getClass().getMethod("getAppVersion").invoke(app);
            Object activePage = app.getClass().getMethod("getActivePage").invoke(app);
            Object uri = activity.getClass().getMethod("getCurrentUri").invoke(activity);
            String route = uri instanceof String ? (String) uri : null;
            String appId = DiagnosticPolicy.appId(id);
            return new Identity(
                    app,
                    activePage,
                    appId,
                    DiagnosticPolicy.appIdState(id),
                    DiagnosticPolicy.page(id instanceof String ? (String) id : null),
                    DiagnosticPolicy.version(version instanceof String ? (String) version : null),
                    DiagnosticPolicy.versionState(version),
                    DiagnosticPolicy.versionFingerprint(version),
                    DiagnosticPolicy.routeKey(route),
                    DiagnosticPolicy.routeCategory(route),
                    readExistingMainPackage(app, appId));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Identity.unknown();
        }
    }

    private static PackageIdentity readExistingMainPackage(Object app, String appId) {
        if ("unknown".equals(appId)) return PackageIdentity.unavailable("app-id-unavailable");
        try {
            ClassLoader loader = app.getClass().getClassLoader();
            Class<?> managerType =
                    Class.forName(
                            "com.alibaba.ariver.resource.runtime.ResourceContextManager",
                            false,
                            loader);
            // get/getRef缺失时会创建ResourceContext；这里只读取已存在的单例与map条目。
            Field instanceField = managerType.getDeclaredField("instance");
            instanceField.setAccessible(true);
            Object manager = instanceField.get(null);
            if (manager == null) return PackageIdentity.unavailable("manager-unavailable");
            Object startToken = app.getClass().getMethod("getStartToken").invoke(app);
            if (!(startToken instanceof Long token))
                return PackageIdentity.unavailable("start-token-unavailable");
            Field mapField = managerType.getDeclaredField("resourceContextMap");
            mapField.setAccessible(true);
            Object reference;
            synchronized (manager) {
                Object rawMap = mapField.get(manager);
                if (!(rawMap instanceof Map<?, ?> map))
                    return PackageIdentity.unavailable("context-unavailable");
                Object key =
                        managerType
                                .getMethod("getContextKey", String.class, String.class)
                                .invoke(manager, appId, Long.toString(token));
                reference = map.get(key);
            }
            if (reference == null) return PackageIdentity.unavailable("context-unavailable");
            Class<?> refType =
                    Class.forName("com.alibaba.ariver.kernel.common.RefAware", false, loader);
            if (!refType.isInstance(reference))
                return PackageIdentity.unavailable("context-unavailable");
            Object context = refType.getMethod("get").invoke(reference);
            Class<?> contextType =
                    Class.forName("com.alibaba.ariver.resource.api.ResourceContext", false, loader);
            if (context == null || !contextType.isInstance(context))
                return PackageIdentity.unavailable("context-unavailable");
            Object owner = contextType.getMethod("getApp").invoke(context);
            Object contextId = contextType.getMethod("getAppId").invoke(context);
            OwnerEvidence ownership =
                    inspectPackageOwner(owner, app, appId, token, contextId, loader);
            if (!ownership.ownerSame || !ownership.contextAppIdMatches) {
                return PackageIdentity.unavailable("context-mismatch", ownership);
            }
            Object resourcePackage = contextType.getMethod("getMainPackage").invoke(context);
            Class<?> packageType =
                    Class.forName(
                            "com.alibaba.ariver.resource.api.content.ResourcePackage",
                            false,
                            loader);
            if (resourcePackage == null || !packageType.isInstance(resourcePackage))
                return PackageIdentity.unavailable("package-unavailable", ownership);
            Object packageId = packageType.getMethod("appId").invoke(resourcePackage);
            if (!appId.equals(packageId))
                return PackageIdentity.unavailable("package-mismatch", ownership);
            Object version = packageType.getMethod("version").invoke(resourcePackage);
            return new PackageIdentity(
                    resourcePackage,
                    "available",
                    DiagnosticPolicy.version(version instanceof String ? (String) version : null),
                    DiagnosticPolicy.versionState(version),
                    DiagnosticPolicy.versionFingerprint(version),
                    ownership);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return PackageIdentity.unavailable("api-unavailable");
        }
    }

    private static OwnerEvidence inspectPackageOwner(
            Object owner,
            Object app,
            String appId,
            Long token,
            Object contextId,
            ClassLoader loader) {
        boolean idObserved = false, tokenObserved = false, idMatches = false, tokenMatches = false;
        try {
            Class<?> appType = Class.forName("com.alibaba.ariver.app.api.App", false, loader);
            if (owner != null && appType.isInstance(owner)) {
                Object ownerId = appType.getMethod("getAppId").invoke(owner);
                idObserved = true;
                idMatches = DiagnosticPolicy.sameAppId(appId, ownerId);
                Object ownerToken = appType.getMethod("getStartToken").invoke(owner);
                tokenObserved = true;
                tokenMatches = DiagnosticPolicy.sameStartToken(token, ownerToken);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 只输出匹配布尔值和是否取得证据，不输出owner实现类、appid或token原文。
        }
        return new OwnerEvidence(
                owner,
                true,
                owner == null,
                owner == app,
                idObserved,
                tokenObserved,
                idMatches,
                tokenMatches,
                DiagnosticPolicy.sameAppId(appId, contextId));
    }

    private View decorRoot() {
        return activity == null ? null : activity.getWindow().getDecorView();
    }

    private boolean diagnosticsEnabled() {
        return !closed && activity != null && config.get().diagnostics;
    }

    private static Class<?> findWebViewClass(View view) {
        Class<?> type = view.getClass();
        while (type != null) {
            if (WEB_VIEW.equals(type.getName())) return type;
            type = type.getSuperclass();
        }
        return null;
    }

    private static int clampCount(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    private static String mainRelativeNodePath(String value) {
        return "0".equals(value) || "0.0".equals(value) || "1.0.0".equals(value) ? value : "none";
    }

    private void runDiagnosticSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            closed = true;
            queued = false;
            callbacks.clear();
            epoch.pause();
            activity = null;
            try {
                main.removeCallbacks(inspectionTask);
                Log.i(TAG, "diagnostics=stopped reason=runtime-unavailable");
            } catch (RuntimeException | LinkageError ignoredAgain) {
                // 连日志系统不可用时也不影响宿主。
            }
        }
    }

    // 身份快照只保留内存归属和固定诊断元数据。

    private static final class Identity {
        final Object app;
        final Object activePage;
        final String appId;
        final String appIdState;
        final String page;
        final String version;
        final String versionState;
        final String versionFingerprint;
        final byte[] route;
        final String routeCategory;
        final PackageIdentity resourcePackage;

        Identity(
                Object app,
                Object activePage,
                String appId,
                String appIdState,
                String page,
                String version,
                String versionState,
                String versionFingerprint,
                byte[] route,
                String routeCategory,
                PackageIdentity resourcePackage) {
            this.app = app;
            this.activePage = activePage;
            this.appId = appId;
            this.appIdState = appIdState;
            this.page = page;
            this.version = version;
            this.versionState = versionState;
            this.versionFingerprint = versionFingerprint;
            this.route = route;
            this.routeCategory = routeCategory;
            this.resourcePackage = resourcePackage;
        }

        static Identity unknown() {
            return new Identity(
                    null,
                    null,
                    "unknown",
                    "unavailable",
                    "unknown",
                    "unknown",
                    "unavailable",
                    "unknown",
                    null,
                    "unavailable",
                    PackageIdentity.unavailable("api-unavailable"));
        }

        String diagnosticMetadata() {
            return "page="
                    + page
                    + " appId="
                    + appId
                    + " appIdState="
                    + appIdState
                    + " resourceVersion="
                    + version
                    + " versionState="
                    + versionState
                    + " versionFingerprint="
                    + versionFingerprint
                    + " packageSource=existing-main-package packageState="
                    + resourcePackage.state
                    + " packageVersion="
                    + resourcePackage.version
                    + " packageVersionState="
                    + resourcePackage.versionState
                    + " packageVersionFingerprint="
                    + resourcePackage.fingerprint
                    + resourcePackage.ownership.diagnosticMetadata()
                    + " routeCategory="
                    + routeCategory
                    + " routeFingerprint="
                    + DiagnosticPolicy.routeFingerprint(route)
                    + " metadataSource=host-app-api";
        }

        boolean hasReliableOwner() {
            return app != null && activePage != null && route != null;
        }

        boolean matches(Identity current) {
            return hasReliableOwner()
                    && current.hasReliableOwner()
                    && app == current.app
                    && activePage == current.activePage
                    && appId.equals(current.appId)
                    && appIdState.equals(current.appIdState)
                    && !"over-limit".equals(versionState)
                    && !"over-limit".equals(current.versionState)
                    && page.equals(current.page)
                    && version.equals(current.version)
                    && versionState.equals(current.versionState)
                    && versionFingerprint.equals(current.versionFingerprint)
                    && resourcePackage.matches(current.resourcePackage)
                    && DiagnosticPolicy.sameRoute(route, current.route);
        }
    }

    private static final class PackageIdentity {
        final Object resourcePackage;
        final String state;
        final String version;
        final String versionState;
        final String fingerprint;
        final OwnerEvidence ownership;

        PackageIdentity(
                Object resourcePackage,
                String state,
                String version,
                String versionState,
                String fingerprint,
                OwnerEvidence ownership) {
            this.resourcePackage = resourcePackage;
            this.state = state;
            this.version = version;
            this.versionState = versionState;
            this.fingerprint = fingerprint;
            this.ownership = ownership;
        }

        static PackageIdentity unavailable(String state) {
            return unavailable(state, OwnerEvidence.unobserved());
        }

        static PackageIdentity unavailable(String state, OwnerEvidence ownership) {
            return new PackageIdentity(null, state, "unknown", "unavailable", "unknown", ownership);
        }

        boolean matches(PackageIdentity current) {
            return resourcePackage == current.resourcePackage
                    && state.equals(current.state)
                    && !"over-limit".equals(versionState)
                    && !"over-limit".equals(current.versionState)
                    && version.equals(current.version)
                    && versionState.equals(current.versionState)
                    && fingerprint.equals(current.fingerprint)
                    && ownership.matches(current.ownership);
        }
    }

    private static final class OwnerEvidence {
        final Object owner;
        final boolean observed;
        final boolean ownerNull;
        final boolean ownerSame;
        final boolean ownerAppIdObserved;
        final boolean ownerStartTokenObserved;
        final boolean ownerAppIdMatches;
        final boolean ownerStartTokenMatches;
        final boolean contextAppIdMatches;

        OwnerEvidence(
                Object owner,
                boolean observed,
                boolean ownerNull,
                boolean ownerSame,
                boolean ownerAppIdObserved,
                boolean ownerStartTokenObserved,
                boolean ownerAppIdMatches,
                boolean ownerStartTokenMatches,
                boolean contextAppIdMatches) {
            this.owner = owner;
            this.observed = observed;
            this.ownerNull = ownerNull;
            this.ownerSame = ownerSame;
            this.ownerAppIdObserved = ownerAppIdObserved;
            this.ownerStartTokenObserved = ownerStartTokenObserved;
            this.ownerAppIdMatches = ownerAppIdMatches;
            this.ownerStartTokenMatches = ownerStartTokenMatches;
            this.contextAppIdMatches = contextAppIdMatches;
        }

        static OwnerEvidence unobserved() {
            return new OwnerEvidence(null, false, false, false, false, false, false, false, false);
        }

        String diagnosticMetadata() {
            return " ownerObserved="
                    + observed
                    + " ownerNull="
                    + ownerNull
                    + " ownerSame="
                    + ownerSame
                    + " ownerAppIdObserved="
                    + ownerAppIdObserved
                    + " ownerStartTokenObserved="
                    + ownerStartTokenObserved
                    + " ownerAppIdMatches="
                    + ownerAppIdMatches
                    + " ownerStartTokenMatches="
                    + ownerStartTokenMatches
                    + " contextAppIdMatches="
                    + contextAppIdMatches;
        }

        boolean matches(OwnerEvidence other) {
            return owner == other.owner
                    && observed == other.observed
                    && ownerNull == other.ownerNull
                    && ownerSame == other.ownerSame
                    && ownerAppIdObserved == other.ownerAppIdObserved
                    && ownerStartTokenObserved == other.ownerStartTokenObserved
                    && ownerAppIdMatches == other.ownerAppIdMatches
                    && ownerStartTokenMatches == other.ownerStartTokenMatches
                    && contextAppIdMatches == other.contextAppIdMatches;
        }
    }
}
