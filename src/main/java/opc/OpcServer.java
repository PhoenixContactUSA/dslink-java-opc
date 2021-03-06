package opc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.handler.Handler;

public abstract class OpcServer {
	protected static final Logger LOGGER = LoggerFactory.getLogger(OpcServer.class);
	
	protected static final boolean LAZY_LOAD = false;
	private static final long PING_DELAY_SECONDS = 5;
	
	protected final OpcConn conn;
	protected final Node node;
	protected boolean stopped;
	protected final Node statnode;
	protected final Map<String, Node> itemNodes = new ConcurrentHashMap<String, Node>();
	private ScheduledFuture<?> pingFuture = null;
	private boolean initializing = true;
	private boolean dontPing = false;
	private int failCount = 0;
	private int pingCyclesToSkip = 0;
	
	OpcServer(OpcConn c, Node n) {
		this.conn = c;
		this.node = n;
		this.stopped = false;
		
		this.statnode = node.createChild("STATUS", true).setValueType(ValueType.STRING).setValue(new Value("")).build();
		this.statnode.setSerializable(false);
		
		Action act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove", true).setAction(act).build().setSerializable(false);
	}
	
	void init() {
		synchronized(this) {
			initializing = true;
			dontPing = false;
		}
		statnode.setValue(new Value("Connecting..."));
		
		String host = conn.node.getAttribute("host").getString();
		String domain = conn.node.getAttribute("domain").getString();
		String user = conn.node.getAttribute("user").getString();
		String pass = String.valueOf(conn.node.getPassword());
				
		Action act = getEditAction(host, domain, user, pass);
		Node anode = node.getChild("edit", true);
		if (anode == null) node.createChild("edit", true).setAction(act).build().setSerializable(false);
		else anode.setAction(act);
		
		connect(host, domain, user, pass);
		
		if (!stopped) {
        	statnode.setValue(new Value("Connected"));
        	node.removeChild("connect", true);
        	
        	act = new Action(Permission.READ, new RefreshHandler());
    		anode = node.getChild("refresh", true);
    		if (anode == null) node.createChild("refresh", true).setAction(act).build().setSerializable(false);
    		else anode.setAction(act);
    		
    		act = new Action(Permission.READ, new Handler<ActionResult>(){
    			public void handle(ActionResult event) {
    				synchronized(this) {
    					dontPing = true;
    				}
    				stop();
    			}
    		});
    		anode = node.getChild("disconnect", true);
    		if (anode == null) node.createChild("disconnect", true).setAction(act).build().setSerializable(false);
    		else anode.setAction(act);
    		
    		onConnected();
    		synchronized(this) {
    			failCount = 0;
    			pingCyclesToSkip = 0;
    		}
    		
		} else {
        	act = new Action(Permission.READ, new RefreshHandler());
    		anode = node.getChild("connect", true);
    		if (anode == null) node.createChild("connect", true).setAction(act).build().setSerializable(false);
    		else anode.setAction(act);
    		synchronized(this) {
    			failCount += 1;
    		}
        }
		
		act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				clear();
			}
		});
		anode = node.getChild("clear", true);
		if (anode == null) node.createChild("clear", true).setAction(act).build().setSerializable(false);
		else anode.setAction(act);
		
		if (pingFuture == null) {
			pingFuture = Objects.getDaemonThreadPool().scheduleWithFixedDelay(new Runnable() {
				@Override
				public void run() {
					ping();
				}
			}, PING_DELAY_SECONDS, PING_DELAY_SECONDS, TimeUnit.SECONDS);
		}
		synchronized(this) {
			initializing = false;
		}
	}
	
	protected abstract void connect(String host, String domain, String user, String pass);
	
	protected abstract void onConnected();
	
	protected abstract boolean isConnected();
	
	protected abstract Action getEditAction(String host, String domain, String user, String pass);
	
	private void ping() {
		synchronized(this) {
			if (pingCyclesToSkip > 0) {
//				LOGGER.info("Skipping Ping because Ping Cycles to skip = " + pingCyclesToSkip);
				pingCyclesToSkip--;
			} else if (!initializing && !dontPing) {
				if (!isConnected()) {
//					LOGGER.info("Ping result bad, fail count =" + failCount);
					LOGGER.debug("Not connected to server, scheduling reconnect");
					pingCyclesToSkip = Math.min(failCount, 60);
					Objects.getDaemonThreadPool().schedule(new Runnable() {
						@Override
						public void run() {
							stop();
							init();
						}
					}, 0, TimeUnit.SECONDS);
				} else {
//					LOGGER.info("Ping result good");
				}
			} else {
//				LOGGER.info("Skipping Ping because initializing or stopped");
			}
		}
	}
	
	protected void clear() {
		itemNodes.clear();
		if (node.getChildren() != null) {
			for (Node child: node.getChildren().values()) {
				if (child.getAction() == null && !child.getName().equals("STATUS")) node.removeChild(child, false);
			}
		}
	} 
	
	public void setupNode(final Node child) {
		//Item item = writeGroup.addItem(child.getAttribute("item id").getString());
		itemNodes.put(child.getAttribute("item id").getString(), child);
		child.getListener().setOnSubscribeHandler(new Handler<Node>(){
			public void handle(Node event) {
				addItemSub(child);
			}
		});
		child.getListener().setOnUnsubscribeHandler(new Handler<Node>(){
			public void handle(Node event) {
				removeItemSub(child);
			}
		});
		
		Value ar = child.getAttribute("accessRights");
		if (ar != null && ("readWritable".equals(ar.getString()) || "writable".equals(ar.getString()))) {
			child.setWritable(Writable.WRITE);
			child.getListener().setValueHandler(getSetHandler(child));
		} else {
			child.setWritable(Writable.NEVER);
		}
		
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				itemNodes.remove(child.getAttribute("item id").getString());
				removeChildNode(child);
			}
		});
		child.createChild("remove", true).setAction(act).build().setSerializable(false);
		
	}
	
	protected void removeChildNode(Node child) {
		Node parent = child.getParent();
		Map<String, Node> siblings = parent.getChildren();
		child.delete(false);
		if (parent != node && (siblings == null || siblings.isEmpty())) {
			removeChildNode(parent);
		}
	}
	
	public abstract void addItemSub(Node event);
	
	public abstract void removeItemSub(Node event);
	
	protected abstract Handler<ValuePair> getSetHandler(Node child);
	
	protected class RefreshHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			stop();
			init();
		}
	}
	
	protected class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}
	
	protected void remove() {
		stop();
		node.clearChildren();
		node.getParent().removeChild(node, false);
		pingFuture.cancel(true);
	}
	
	protected void stop() {
		stopped = true;
		statnode.setValue(new Value("Not Connected"));
//		clear();
		node.removeChild("refresh", true);
		node.removeChild("disconnect", true);
		Action act = new Action(Permission.READ, new RefreshHandler());
		Node anode = node.getChild("connect", true);
		if (anode == null) node.createChild("connect", true).setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	public void restoreLastSession() {
		init();
	}
	

}
