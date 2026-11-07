package calc.web.compat;

import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/** Found by TeaVM through META-INF/services, the way its class library declares its own extension. */
public final class CompatPlugin implements TeaVMPlugin {
    @Override
    public void install(TeaVMHost host) {
        host.add(new CompatCallRewriter());
    }
}
