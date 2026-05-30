package cn.classfun.droidvm.daemon.ipc.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONArray;
import org.json.JSONObject;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

/**
 * 运行时热更新某个 VM 的端口转发规则。
 * 参数：{@code vm_id}、{@code rules}（端口转发规则数组，契约同 {@code port_forwards} 字段）。
 * 收到后更新该 VM 的内存配置并即时同步 iptables，返回当前生效的转发列表。
 * 配置的持久化由前端负责（写共享 vms.json）。
 */
@AutoService(RequestHandler.class)
public final class PortForwardSetHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_port_forward_set";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var id = params.optString("vm_id", "");
        if (id.isEmpty())
            throw new RequestException("missing vm_id");
        var vm = request.getContext().getVMs().findById(id);
        if (vm == null)
            throw new RequestException(fmt("VM %s not found", id));
        var rules = params.optJSONArray("rules");
        if (rules == null) rules = new JSONArray();
        vm.applyPortForwards(rules);
        var data = new JSONObject();
        data.put("active", vm.getActivePortForwards());
        request.res().put("data", data);
    }
}
