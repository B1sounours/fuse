package stream;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;

import se.rupy.http.Async;
import se.rupy.http.Daemon;
import se.rupy.http.Deploy;
import se.rupy.http.Event;
import se.rupy.http.Root;
import stream.Node;

/* messages:
 * join					-> join|<key>
 * 						-> fail|<name> contains bad characters
 * 						-> fail|<name> already registered
 * salt					-> salt|<salt>
 * auth|<salt>|<hash>	-> auth|Success
 * 						-> fail|User not found
 * 						-> fail|Wrong hash
 * make|<size>			-> make|Success
 * 						-> fail|User not in lobby
 * list					-> list|<name>|<name>|...
 * room|<name>			-> room|Success // join room
 * 						-> fail|Room not found
 * 						-> fail|Room is full
 * exit					-> exit|Success
 * 						-> fail|User in lobby
 * chat|<text>			-> <nothing> users in same room get chat|<name>|<text>
 * move|<data>			-> <nothing> users in same room get move|<name>|<data>
 */

public class Game implements Node {
	ConcurrentHashMap salts = new ConcurrentHashMap();
	ConcurrentHashMap rooms = new ConcurrentHashMap();
	ConcurrentHashMap users = new ConcurrentHashMap();
	
	static Room lobby = new Room(null, 1024);
	static Daemon daemon;
	static Node node;

	public void call(Daemon daemon, Node node) throws Exception {
		this.daemon = daemon;
		this.node = node;
	}

	public String push(final Event event, final String name, String message) throws Exception {
		System.err.println(name + " " + message);

		if(name.length() == 0)
			return "fail|Name missing";
		
		String[] split = message.split("\\|");
		
		if(message.startsWith("join")) {
			Async.Work user = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					call.post("/node", "Host:fuse.rupy.se", ("json={\"name\":\"" + name + "\"}&sort=key,name&create").getBytes("utf-8"));
				}

				public void read(String host, String body) throws Exception {
					System.out.println(body);

					if(body.indexOf("Validation") > 0) {
						String message = body.substring(body.indexOf("[") + 1, body.indexOf("]"));

						System.out.println("Validation " + message);
						
						if(message.startsWith("name"))
							event.query().put("fail", "fail|" + message.substring(message.indexOf("=") + 1) + " contains bad characters");
					}
					else if(body.indexOf("Collision") > 0) {
						String message = body.substring(body.indexOf("[") + 1, body.indexOf("]"));

						System.out.println("Collision " + message);
						
						if(message.startsWith("name"))
							event.query().put("fail", "fail|" + message.substring(message.indexOf("=") + 1) + " already registered");
					}
					else {
						JSONObject user = new JSONObject(body);

						String key = user.getString("key");

						event.query().put("data", "join|" + key);
					}
					
					event.reply().wakeup();
				}

				public void fail(String host, Exception e) throws Exception {
					e.printStackTrace();
				}
			};

			event.daemon().client().send("localhost", user, 30);

			return "hold";
		}

		if(message.startsWith("salt")) {
			String salt = Event.random(8);
			salts.put(salt, "");
			return "salt|" + salt;
		}

		if(message.startsWith("auth")) {
			String salt = split[1];
			String hash = split[2].toLowerCase();
			
			if(name.length() > 0 && hash.length() > 0) {
				File file = new File(Root.home() + "/node/user/name" + Root.path(name));

				if(!file.exists()) {
					System.out.println(file);
					return "fail|User not found.";
				}

				JSONObject json = new JSONObject(Root.file(file));

				if(salts.remove(salt) != null && hash.equals(Deploy.hash(json.getString("key") + salt, "MD5"))) {
					User user = new User(name);
					users.put(user.name, user);
					user.move(null, lobby);
					return "auth|Success";
				}
				else
					return "fail|Wrong hash";
			}
		}
		
		User user = (User) users.get(name);
		
		if(event.query().header("host").equals("fuse.radiomesh.org") && user == null)
			return "fail|User '" + name + "' not authorized";
		
		if(message.startsWith("make")) {
			if(user.room.user != null)
				return "fail|User not in lobby";
			
			int size = Integer.parseInt(split[1]);
			
			Room room = new Room(user, size);
			rooms.put(room.user.name, room);
			
			user.move(lobby, room);
			
			return "make|Success";
		}
		
		if(message.startsWith("list")) {
			Iterator it = rooms.values().iterator();
			StringBuilder builder = new StringBuilder("list");
			
			while(it.hasNext()) {
				Room room = (Room) it.next();
				builder.append("|" + room.user.name);
			}
			
			return builder.toString();
		}
		
		if(message.startsWith("room")) {
			Room room = (Room) rooms.get(split[1]);
				
			if(room == null)
				return "fail|Room not found";
			
			if(room.users.size() == room.size)
				return "fail|Room is full";
			
			user.move(user.room, room);
		}
		
		if(message.startsWith("exit")) {
			if(user.room.user == null)
				return "fail|User in lobby";
			
			user.move(user.room, lobby);
		}
		
		if(message.startsWith("chat")) {
			if(user == null)
				lobby.send("chat|" + name + "|" + split[1]);
			else
				user.room.send("chat|" + name + "|" + split[1]);
			
			return null;
		}
		
		if(message.startsWith("move")) {
			if(user == null)
				lobby.send("chat|" + name + "|" + split[1]);
			else
				user.room.send(user, "move|" + name + "|" + split[1]);
			
			return null;
		}
		
		return "fail|Method '" + split[0] + "' not found";
	}

	public static class User {
		String name;
		Room room;
		
		User(String name) {
			this.name = name;
		}
		
		void move(Room from, Room to) throws Exception {
			if(from != null) {
				from.remove(this);
				from.send("exit|" + name);
			}
			
			if(to != null) {
				this.room = to;
			
				to.add(this);
				to.send(this, "room|" + name);
			}
		}
	}
	
	public static class Room {
		ConcurrentHashMap users = new ConcurrentHashMap();
		User user;
		int size;
		
		Room(User user, int size) {
			this.user = user;
			this.size = size;
		}
		
		void send(String message) throws Exception {
			send(null, message);
		}
		
		void send(User from, String message) throws Exception {
			Iterator it = users.values().iterator();
			
			while(it.hasNext()) {
				User user = (User) it.next();
				
				if(from == null || !from.name.equals(user.name))
					node.push(null, user.name, message);
			}
		}
		
		void add(User user) {
			users.put(user.name, user);
		}
		
		void remove(User user) {
			users.remove(user.name);
		}
	}
	
	public void broadcast(String name, String message) {

	}

	public void exit() {
		//daemon.remove(this);
	}
}