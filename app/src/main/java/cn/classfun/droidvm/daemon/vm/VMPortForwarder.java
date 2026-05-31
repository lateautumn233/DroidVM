package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.NetworkInstanceStore;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * 管理单个 VM 的端口转发（host -> guest）iptables DNAT 规则。
 * VM 进入 RUNNING 后启动：轮询 DHCP 租约/邻居表，按网卡 MAC 解析 guest IP，
 * 然后下发规则；VM 退出时撤销全部已下发的规则。
 *
 * <p>规则来源于 VM 配置的 {@code port_forwards} 数组，每项字段：
 * <ul>
 *   <li>{@code protocol}: tcp | udp（默认 tcp）</li>
 *   <li>{@code host_port}: 宿主机监听端口（必填）</li>
 *   <li>{@code guest_port}: guest 目标端口（默认与 host_port 相同）</li>
 *   <li>{@code host_ip}: 可选，仅转发发往该宿主地址的流量；留空=全部</li>
 *   <li>{@code network_id}: 可选，多网卡时指定走哪个网卡；留空=首个网卡</li>
 *   <li>{@code guest_ip}: 可选，手动指定 guest IP，跳过自动发现</li>
 *   <li>{@code enabled}: 可选，默认 true</li>
 * </ul>
 */
final class VMPortForwarder {
    private static final String TAG = "VMPortForwarder";
    private static final int RESOLVE_MAX_ATTEMPTS = 30;
    private static final long RESOLVE_INTERVAL_MS = 1000L;

    private final VMInstance vm;
    private final NetworkInstanceStore networkStore;
    private final List<Applied> applied = new ArrayList<>();
    private volatile boolean running = false;
    private Thread thread;

    VMPortForwarder(@NonNull VMInstance vm, @Nullable NetworkInstanceStore networkStore) {
        this.vm = vm;
        this.networkStore = networkStore;
    }

    boolean hasRules() {
        var pf = vm.item.opt("port_forwards", null);
        return pf != null && !pf.isEmpty();
    }

    synchronized void start() {
        if (running) return;
        if (!hasRules()) return;
        if (networkStore == null) {
            Log.w(TAG, fmt("VM %s has port forwards but no network store", vm.getName()));
            return;
        }
        running = true;
        thread = new Thread(this::loop, fmt("PF-%s", vm.getId()));
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        notifyAll();
        var t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
        removeAll();
    }

    @NonNull
    JSONArray snapshotApplied() {
        var arr = new JSONArray();
        synchronized (applied) {
            for (var a : applied) {
                try {
                    var o = new JSONObject();
                    o.put("protocol", a.protocol);
                    o.put("host_ip", a.hostIp == null ? "" : a.hostIp);
                    o.put("host_port", a.hostPort);
                    o.put("guest_ip", a.guestIp);
                    o.put("guest_port", a.guestPort);
                    arr.put(o);
                } catch (JSONException ignored) {
                }
            }
        }
        return arr;
    }

    /**
     * 运行时热同步当前配置：
     * <ul>
     *   <li>已在转发（VM 启动时已有规则）→ 唤醒轮询线程，由它持 {@code this} 重新对账；</li>
     *   <li>此前因无规则未启动 → {@link #start()} 按新配置启动轮询应用。</li>
     * </ul>
     * 对账统一在轮询线程内完成（单写者），避免与 {@link #loop()} 并发改 {@link #applied} 产生竞态；
     * {@code notifyAll} 与 {@code wait} 同持 {@code this} 监视器，保证轮询线程能可见 {@code item} 的最新配置。
     * 供 {@link VMInstance#applyPortForwards} 在运行时改规则后调用。
     */
    synchronized void sync() {
        if (running) notifyAll();
        else start();
    }

    /**
     * 以 VM 当前 {@code port_forwards} 配置为准，对已下发规则做一次增量对账：
     * 撤销已删除/已改 host 端的规则、下发新增或新解析出的规则、切换改了 target 的规则。
     * 对「配置仍在但此刻解析不出 guest IP」的 host 绑定保留其旧条目，待解析成功后在第 2 步原子切换，
     * 避免热改 guest_ip/网卡时把原本正常的转发误删（旧路径 reapply 先删后加、无重试会丢规则）。
     * 仅由轮询线程在持 {@code this} 时调用，是 {@link #applied} 的唯一写入者（除 {@link #stop()} 清理）。
     *
     * @return 是否仍有「已配置但当前无法解析 guest IP」的规则，调用方据此决定是否继续轮询重试。
     */
    private boolean reconcile() {
        if (!running || networkStore == null) return false;
        var rules = parseRules();
        // 锁外解析目标，避免持 applied 锁期间做 DHCP/邻居表查询
        var desired = new ArrayList<Applied>();
        boolean hasUnresolved = false;
        for (var rule : rules) {
            var target = resolveTarget(rule);
            if (target == null) {
                hasUnresolved = true;
                continue;
            }
            desired.add(new Applied(target.bridge, target.ip, rule.protocol,
                rule.hostIp, rule.hostPort, rule.guestPort));
        }
        synchronized (applied) {
            // 1. 撤销：与某条 desired 完全一致 → 保留；host 绑定仍在配置中（仅暂时解析不出）
            //    → 保留旧条目，待第 2 步解析成功后切换；否则（规则被删/禁用/改了 host 端）→ removeForward
            applied.removeIf(a -> {
                for (var d : desired)
                    if (sameForward(a, d)) return false;
                if (hostBindingConfigured(a, rules)) return false;
                networkStore.firewall.removeForward(
                    a.bridge, a.guestIp, a.protocol, a.hostIp, a.hostPort, a.guestPort);
                Log.i(TAG, fmt("VM %s: removed forward %s :%d -> %s:%d",
                    vm.getName(), a.protocol, a.hostPort, a.guestIp, a.guestPort));
                return true;
            });
            // 2. 下发 desired 中尚未生效的；若同 host 绑定已有旧 target，先撤旧再下发新（原子切换）
            for (var d : desired) {
                boolean exists = false;
                for (var a : applied)
                    if (sameForward(a, d)) {
                        exists = true;
                        break;
                    }
                if (exists) continue;
                applied.removeIf(a -> {
                    if (!sameHostBinding(a, d)) return false;
                    networkStore.firewall.removeForward(
                        a.bridge, a.guestIp, a.protocol, a.hostIp, a.hostPort, a.guestPort);
                    Log.i(TAG, fmt("VM %s: switched forward %s :%d off %s:%d",
                        vm.getName(), a.protocol, a.hostPort, a.guestIp, a.guestPort));
                    return true;
                });
                boolean ok = networkStore.firewall.applyForward(
                    d.bridge, d.guestIp, d.protocol, d.hostIp, d.hostPort, d.guestPort);
                if (ok) {
                    applied.add(d);
                    Log.i(TAG, fmt("VM %s: forward %s :%d -> %s:%d",
                        vm.getName(), d.protocol, d.hostPort, d.guestIp, d.guestPort));
                } else {
                    Log.w(TAG, fmt("VM %s: failed to apply forward %s :%d",
                        vm.getName(), d.protocol, d.hostPort));
                }
            }
        }
        return hasUnresolved;
    }

    private static boolean sameForward(@NonNull Applied a, @NonNull Applied b) {
        return a.protocol.equals(b.protocol)
            && a.hostPort == b.hostPort
            && a.guestPort == b.guestPort
            && eq(a.hostIp, b.hostIp)
            && eq(a.guestIp, b.guestIp)
            && eq(a.bridge, b.bridge);
    }

    /**
     * 两条已下发规则是否共享同一 host 绑定 (protocol, host_ip, host_port)——即 DNAT 入口。
     * 用于「target 变更」的原子切换：同入口、不同 target 时先撤旧再下发新。
     */
    private static boolean sameHostBinding(@NonNull Applied a, @NonNull Applied b) {
        return a.protocol.equals(b.protocol) && a.hostPort == b.hostPort && eq(a.hostIp, b.hostIp);
    }

    /** 已下发规则 a 的 host 绑定是否仍存在于当前配置（即便此刻解析不出 guest IP）。 */
    private static boolean hostBindingConfigured(@NonNull Applied a, @NonNull List<Rule> rules) {
        for (var r : rules)
            if (a.protocol.equals(r.protocol) && a.hostPort == r.hostPort && eq(a.hostIp, r.hostIp))
                return true;
        return false;
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 轮询线程主体：持 {@code this} 反复 {@link #reconcile()}（每轮重新读取配置，故删除/修改即时可见）。
     * 全部规则解析完成后 {@code wait()} 休眠，由 {@link #sync()}/{@link #stop()} 唤醒；仍有未解析规则时
     * 限时等待后重试，超过 {@link #RESOLVE_MAX_ATTEMPTS} 次转为休眠（下次配置变更会再唤醒并重置重试）。
     * 线程在整个 RUNNING 期间存活，避免「线程退出后又来新规则却无人轮询」的窗口。
     */
    private void loop() {
        try {
            int attempts = 0;
            synchronized (this) {
                while (running && vm.getState() == VMState.RUNNING) {
                    boolean hasUnresolved = reconcile();
                    if (!running) break;
                    if (hasUnresolved && attempts < RESOLVE_MAX_ATTEMPTS) {
                        attempts++;
                        wait(RESOLVE_INTERVAL_MS);
                    } else {
                        if (hasUnresolved)
                            Log.w(TAG, fmt("VM %s: gave up auto-resolving guest IP for some port"
                                    + " forwards after %d attempts; will retry on next config change",
                                vm.getName(), attempts));
                        attempts = 0;
                        wait();
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void removeAll() {
        synchronized (applied) {
            if (networkStore != null)
                for (var a : applied)
                    networkStore.firewall.removeForward(
                        a.bridge, a.guestIp, a.protocol, a.hostIp, a.hostPort, a.guestPort);
            applied.clear();
        }
    }

    @NonNull
    private List<Rule> parseRules() {
        var list = new ArrayList<Rule>();
        var pf = vm.item.opt("port_forwards", null);
        if (pf == null || !pf.is(DataItem.Type.ARRAY)) return list;
        var seen = new HashSet<String>();
        for (var iter : pf) {
            var r = iter.getValue();
            if (!r.is(DataItem.Type.OBJECT)) continue;
            if (!r.optBoolean("enabled", true)) continue;
            var protocol = r.optString("protocol", "tcp");
            if (protocol == null || protocol.isEmpty()) protocol = "tcp";
            protocol = protocol.toLowerCase(Locale.ROOT);
            if (!protocol.equals("tcp") && !protocol.equals("udp")) continue;
            long hostPort = r.optLong("host_port", 0);
            if (hostPort <= 0 || hostPort > 65535) continue;
            long guestPort = r.optLong("guest_port", 0);
            if (guestPort <= 0 || guestPort > 65535) guestPort = hostPort;
            var hostIp = r.optString("host_ip", "");
            if (hostIp == null) hostIp = "";
            // 同一 VM 内按 (protocol, host_ip, host_port) 去重，避免重复 DNAT
            var key = fmt("%s|%s|%d", protocol, hostIp, hostPort);
            if (!seen.add(key)) {
                Log.w(TAG, fmt("VM %s: duplicate port forward %s, skipping", vm.getName(), key));
                continue;
            }
            var rule = new Rule();
            rule.protocol = protocol;
            rule.hostIp = hostIp;
            rule.hostPort = (int) hostPort;
            rule.guestPort = (int) guestPort;
            rule.networkId = r.optString("network_id", "");
            rule.fixedGuestIp = r.optString("guest_ip", "");
            list.add(rule);
        }
        return list;
    }

    @Nullable
    private Target resolveTarget(@NonNull Rule rule) {
        var net = findNetwork(rule.networkId);
        if (net == null) return null;
        var netId = net.optString("network_id", "");
        if (netId == null || netId.isEmpty()) return null;
        var netInst = networkStore.findById(netId);
        if (netInst == null) return null;
        var bridge = netInst.item.optString("bridge_name", "");
        if (bridge == null || bridge.isEmpty()) return null;
        if (rule.fixedGuestIp != null && !rule.fixedGuestIp.isEmpty())
            return new Target(bridge, rule.fixedGuestIp);
        var mac = net.optString("mac_address", "");
        if (mac == null || mac.isEmpty()) return null;
        var ip = resolveGuestIpByMac(netInst, bridge, mac);
        if (ip == null) return null;
        return new Target(bridge, ip);
    }

    @Nullable
    private String resolveGuestIpByMac(
        @NonNull NetworkInstance netInst, @NonNull String bridge, @NonNull String mac) {
        var macLower = mac.toLowerCase(Locale.ROOT);
        // 1. 优先用 dnsmasq DHCP 租约
        var ip = matchMac(networkStore.backend.listDhcpLeases(bridge), macLower, "ip", "mac");
        if (ip != null) return ip;
        // 2. 回退到 ARP 邻居表（适用于静态 IP 且已通信过的 guest）
        return matchMac(netInst.listNeighbors(), macLower, "dst", "lladdr");
    }

    @Nullable
    private static String matchMac(
        @Nullable JSONArray arr, @NonNull String macLower,
        @NonNull String ipKey, @NonNull String macKey) {
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            var o = arr.optJSONObject(i);
            if (o == null) continue;
            var m = o.optString(macKey, "");
            if (!m.isEmpty() && m.toLowerCase(Locale.ROOT).equals(macLower)) {
                var ip = o.optString(ipKey, "");
                if (!ip.isEmpty()) return ip;
            }
        }
        return null;
    }

    @Nullable
    private DataItem findNetwork(@Nullable String networkId) {
        var nets = vm.item.opt("networks", null);
        if (nets == null || !nets.is(DataItem.Type.ARRAY)) return null;
        DataItem first = null;
        for (var iter : nets) {
            var net = iter.getValue();
            var nid = net.optString("network_id", "");
            if (nid == null || nid.isEmpty()) continue;
            if (first == null) first = net;
            if (networkId == null || networkId.isEmpty()) return net;
            if (networkId.equals(nid)) return net;
        }
        return first;
    }

    private static final class Applied {
        final String bridge;
        final String guestIp;
        final String protocol;
        final String hostIp;
        final int hostPort;
        final int guestPort;

        Applied(String bridge, String guestIp, String protocol,
                String hostIp, int hostPort, int guestPort) {
            this.bridge = bridge;
            this.guestIp = guestIp;
            this.protocol = protocol;
            this.hostIp = hostIp;
            this.hostPort = hostPort;
            this.guestPort = guestPort;
        }
    }

    private static final class Rule {
        String protocol;
        String hostIp;
        int hostPort;
        int guestPort;
        String networkId;
        String fixedGuestIp;
    }

    private static final class Target {
        final String bridge;
        final String ip;

        Target(String bridge, String ip) {
            this.bridge = bridge;
            this.ip = ip;
        }
    }
}
