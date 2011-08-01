package hudson.plugins.deploy.websphere;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.plugins.deploy.ContainerAdapter;
import hudson.plugins.deploy.ContainerAdapterDescriptor;

import java.io.IOException;
import java.io.PrintStream;

import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * WebSphere Application Server 6.1
 * 
 * @author Antonio Sanso
 */
public class WAS61Adapter extends ContainerAdapter {
	protected PrintStream log;
	private final String url;
	private static final int HTTP_PREFIX = 7;
	private String hostName;
	private String connectPort;
	private final String targetName;

	@DataBoundConstructor
	public WAS61Adapter(String url, String targetName) {
		this.url = url;
		this.targetName = targetName;
		parseHostnameAndPort();
	}

	public String getContainerId() {
		return "websphereASorND6x";
	}

	@Extension
	public static final class DescriptorImpl extends ContainerAdapterDescriptor {
		@Override
		public String getDisplayName() {
			return "WebSphere AS/ND 6.1";
		}
	}

	@Override
	public boolean redeploy(FilePath war, AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener)
			throws IOException, InterruptedException {
		try {
			this.log = listener.getLogger();
			log.println("[WARNING]Deploying " + war.getName() + " to WebSphere AS/ND 6.1 ");
			Deployer d = new Deployer(hostName, connectPort, targetName);
			d.setLogger(this.log);
			log.println("deploy file :" + war.absolutize().toString());
			d.deploy(war.absolutize().toString());
		} catch (Exception e) {
			listener.fatalError(ResourceBundleHolder.get(WAS61Adapter.class).format("DeployExecutionFailed", e));
			return false;
		}
		return true;
	}

	protected void parseHostnameAndPort() {
		String urlTemp = url.substring(HTTP_PREFIX);
		this.hostName = urlTemp.split(":")[0];
		this.connectPort = urlTemp.split(":")[1];
	}
}
