/**********************************************************************
 * FILE : Deoployer.java
 * CREATE DATE : 2011-7-15
 * DESCRIPTION :
 *		
 *      
 * CHANGE HISTORY LOG
 *---------------------------------------------------------------------
 * NO.|    DATE    |     NAME     |     REASON     | DESCRIPTION
 *---------------------------------------------------------------------
 * 1  | 2011-7-15 |  Sting  |    创建草稿版本
 *---------------------------------------------------------------------              
 **********************************************************************
 */
package hudson.plugins.deploy.websphere;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import javax.management.InstanceNotFoundException;
import javax.management.ListenerNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.Notification;
import javax.management.NotificationFilterSupport;
import javax.management.NotificationListener;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.modelmbean.ModelMBean;

import org.apache.commons.lang.StringUtils;

import com.ibm.websphere.management.AdminClient;
import com.ibm.websphere.management.AdminClientFactory;
import com.ibm.websphere.management.Session;
import com.ibm.websphere.management.application.AppConstants;
import com.ibm.websphere.management.application.AppManagement;
import com.ibm.websphere.management.application.AppManagementProxy;
import com.ibm.websphere.management.application.AppNotification;
import com.ibm.websphere.management.application.client.AppDeploymentController;
import com.ibm.websphere.management.application.client.AppDeploymentTask;
import com.ibm.websphere.management.configservice.ConfigServiceProxy;
import com.ibm.websphere.management.exception.ConnectorException;
import com.ibm.ws.management.application.client.AppInstallHelper;

/**
 * @author Sting
 *         <p>
 *         websphere EAR部署
 */
public class Deployer {
	/**
	 * @param ip
	 * @param port
	 * @param targetName
	 */
	public Deployer(String ip, String port, String targetName) {
		this.ip = ip;
		this.port = port;
		this.targetName = targetName;
	}

	private AdminClient soapClient;
	private AppManagement appManager;
	private MyAdminAppNotificationListener listener;
	public String ip;
	public String port;
	public String targetName;
	protected PrintStream log;

	/**
	 * 
	 * @param earFile
	 *            部署文件
	 * @param targetName
	 *            指定目标名称
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Boolean deploy(File earFile, String ip, String port, String targetName) throws Exception {
		soapClient = connSoapClient(ip, port);
		appManager = AppManagementProxy.getJMXProxyForClient(soapClient);

		Hashtable prefs = new Hashtable();
		prefs.put(AppConstants.APPDEPL_LOCALE, Locale.getDefault());
		Properties defaultBnd = new Properties();
		defaultBnd.put(AppConstants.APPDEPL_DFLTBNDG_VHOST, "default_host");
		prefs.put(AppConstants.APPDEPL_DFLTBNDG, defaultBnd);

		AppDeploymentController controller = AppDeploymentController.readArchive(earFile.getAbsolutePath(), prefs);
		AppDeploymentTask task = controller.getFirstTask();
		while (task != null) {
			String[][] data = task.getTaskData();
			task.setTaskData(data);
			task = controller.getNextTask();
		}
		controller.saveAndClose();

		String appName = (String) controller.getAppDeploymentSavedResults().get("appname");
		log.println("applicaiton name is :" + appName);
		Hashtable options = new Hashtable();
		options.put(AppConstants.APPDEPL_LOCALE, Locale.getDefault());
		Hashtable module2server = new Hashtable();
		module2server.put("*", getTargetServerString(targetName));
		options.put(AppConstants.APPDEPL_MODULE_TO_SERVER, module2server);
		options.put("classLoadingMode", "1");
		options.put("DeleteSourceEar", Boolean.TRUE);

		String serverFile = AppInstallHelper.copyToServer(soapClient, earFile.getAbsolutePath());
		log.println("upload to server:" + serverFile);

		if (appManager.checkIfAppExists(appName, options, null)) {
			log.println("updating server......");
			options.put("contenttype", "app");
			appManager.updateApplication(appName, null, serverFile, "update", options, null);
		} else {
			log.println("fresh intalling ......");
			appManager.installApplication(serverFile, appName, options, null);
		}

		NotificationFilterSupport filterSupport = new NotificationFilterSupport();
		filterSupport.enableType(AppConstants.NotificationType);

		List<String> filterType = new ArrayList<String>();
		filterType.add(AppNotification.INSTALL);
		filterType.add(AppNotification.DISTRIBUTION);
		filterType.add(AppNotification.APP_SYNC);

		listener = new MyAdminAppNotificationListener(soapClient, filterSupport, "Install " + appName, filterType,
				false);
		log.println("wait installing jmx message......");
		synchronized (listener) {
			listener.wait();
		}

		appManager.getDistributionStatus(appName, new Hashtable(), null);
		log.println("wait  distribution jmx message......");
		synchronized (listener) {
			listener.wait();
		}
		if (listener.isSuccessful()) {
			log.println("install sucess!!! start application:" + appName);
			String result = appManager.startApplication(appName, new Hashtable(), null);
			// Object result = soapClient.invoke(listener.getAppMbean(), "startApplication", new Object[] { appName,
			// new Hashtable(), null }, new String[] { String.class.getName(), Hashtable.class.getName(),
			// String.class.getName() });
			this.destroy();
			return true;
		} else {
			return false;
		}
	}

	/**
	 * 
	 * @throws ConnectorException
	 * @throws ListenerNotFoundException
	 * @throws InstanceNotFoundException
	 */
	private void destroy() throws InstanceNotFoundException, ListenerNotFoundException, ConnectorException {
		soapClient.removeNotificationListener(listener.getManageBeanObject(), listener);
		appManager = null;
		soapClient = null;
		listener = null;
	}

	/**
	 * websphere cluster/server string
	 * 
	 * @param targetStr
	 * 
	 * @return
	 * @throws NullPointerException
	 * @throws ConnectorException
	 * @throws MalformedObjectNameException
	 */
	private String getTargetServerString(String targetStr) throws MalformedObjectNameException, ConnectorException,
			NullPointerException {
		StringBuilder targetServerString = new StringBuilder();
		ObjectName mBean = null;
		Set<ObjectName> beans = soapClient.queryNames(new ObjectName("WebSphere:name=" + targetStr + ",*"), null);
		if (beans != null && !beans.isEmpty()) {
			mBean = beans.iterator().next();
		}
		if (mBean != null) {
			targetServerString.append("WebSphere:cell=");
			targetServerString.append(mBean.getKeyProperty("cell"));
			if (mBean.getKeyProperty("type").equals("Cluster")) {
				targetServerString.append(",cluster=");
				targetServerString.append(mBean.getKeyProperty("name"));
			} else {
				targetServerString.append(",node=");
				targetServerString.append(mBean.getKeyProperty("node"));
				targetServerString.append(",server=");
				targetServerString.append(mBean.getKeyProperty("name"));
			}
		}

		return targetServerString.toString();
	}

	/**
	 * 
	 * @param ip
	 * @param port
	 * @return
	 * @throws ConnectorException
	 */
	public AdminClient connSoapClient(String ip, String port) throws ConnectorException {
		Properties props = new Properties();
		props.setProperty(AdminClient.CONNECTOR_HOST, ip);
		props.setProperty(AdminClient.CONNECTOR_PORT, port);
		props.setProperty(AdminClient.CONNECTOR_TYPE, AdminClient.CONNECTOR_TYPE_SOAP);
		props.setProperty(AdminClient.CONNECTOR_SECURITY_ENABLED, "false");
		log.println("connect to " + ip + " :" + port);
		AdminClient client = AdminClientFactory.createAdminClient(props);
		return client;
	}

	/**
	 * @author Sting
	 *         <p>
	 *         实现监听webshpere jmx消息
	 */
	class MyAdminAppNotificationListener implements NotificationListener {
		private AdminClient _adminClient;
		private NotificationFilterSupport _filterSupport;
		private Object _handBack;
		private ObjectName _objectName;
		private List<String> eventTypeToCheck;
		private boolean successful = true;
		private boolean isApplyFilter = true;

		public MyAdminAppNotificationListener(AdminClient client, NotificationFilterSupport support, Object handBack,
				List<String> eventTypeToCheck, boolean isApplyFilter) throws Exception {
			super();
			_adminClient = client;
			_filterSupport = support;
			_handBack = handBack;
			this.eventTypeToCheck = eventTypeToCheck;
			_objectName = (ObjectName) _adminClient.queryNames(new ObjectName("WebSphere:type=AppManagement,*"), null)
					.iterator().next();
			_adminClient.addNotificationListener(_objectName, this, _filterSupport, _handBack);
			this.isApplyFilter = isApplyFilter;
		}

		public void handleNotification(Notification notification, Object handback) {
			AppNotification appNotification = (AppNotification) notification.getUserData();
			log.println(appNotification);
			if ((!isApplyFilter || eventTypeToCheck.contains(appNotification.taskName))
					&& (appNotification.taskStatus.equals(AppNotification.STATUS_COMPLETED) || appNotification.taskStatus
							.equals(AppNotification.STATUS_FAILED))) {
				try {
					// _adminClient.removeNotificationListener(_objectName, this);
					if (appNotification.taskStatus.equals(AppNotification.STATUS_FAILED)) {
						successful = false;
					} else if (appNotification.taskStatus.equals(AppNotification.STATUS_COMPLETED)) {
						successful = true;
					}

					synchronized (this) {
						notifyAll();
					}
				} catch (Exception e) {
				}
			}
		}

		public boolean isSuccessful() {
			return successful;
		}

		public ObjectName getManageBeanObject() {
			return this._objectName;
		}
	}

	/**
	 * @param absolutePath
	 * @param ip
	 * @param port
	 * @param targetStr
	 * @throws Exception
	 */
	public void deploy(String absolutePath) throws Exception {
		File file = new File(absolutePath);
		if (file.exists() && file.isFile()) {
			this.deploy(file, ip, port, targetName);
		}
	}

	public void deploy(File ear) throws Exception {
		if (ear.exists() && ear.isFile()) {
			this.deploy(ear, ip, port, targetName);
		}
	}

	/**
	 * @param log2
	 */
	public void setLogger(PrintStream log2) {
		this.log = log2;
	}
}
