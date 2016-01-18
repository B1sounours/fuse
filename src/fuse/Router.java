package fuse;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import se.rupy.http.Async;
import se.rupy.http.Daemon;
import se.rupy.http.Deploy;
import se.rupy.http.Event;
import se.rupy.http.Output;
import se.rupy.http.Root;
import se.rupy.http.Service;

public class Router implements Node {
	public static ConcurrentLinkedDeque score = new ConcurrentLinkedDeque();

	ConcurrentHashMap users = new ConcurrentHashMap();
	ConcurrentHashMap names = new ConcurrentHashMap();
	ConcurrentHashMap games = new ConcurrentHashMap();

	static ConcurrentHashMap stats = new ConcurrentHashMap();
	static Daemon daemon;
	static Node node;

	public void call(Daemon daemon, Node node) throws Exception {
		this.daemon = daemon;
		this.node = node;
	}

	private synchronized String session() {
		String salt = Event.random(4);

		while(users.get(salt) != null)
			salt = Event.random(4);

		return salt;
	}

	private User session(Event event, String name, String salt, JSONObject json) throws Exception {
		User user = new User(name.length() > 0 ? name : "" + Root.hash(json.getString("key")), salt, json);
		users.put(salt, user);
		names.put(name, user);

		// This uses host.rupy.se specific MaxMind GeoLiteCity.dat
		JSONObject country = new JSONObject((String) daemon.send(null, "{\"type\":\"country\",\"ip\":\"" + event.remote() + "\"}"));
		if(!country.getString("code").equals("--"))
			user.flag = country.getString("code").toLowerCase();
		// End

		return user;
	}

	public void broadcast(String message, boolean finish) throws Exception {
		throw new Exception("Nope");
	}
	public String push(String salt, String data, boolean wake) throws Exception {
		throw new Exception("Nope");
	}
	public boolean wakeup(String name) { return false; }

	public String push(final Event event, String data) throws Exception {
		if(!data.startsWith("send") && !data.startsWith("move"))
			System.err.println("-> '" + data + "'");

		final String[] split = data.split("\\|");

		if(data.startsWith("ping")) {
			return "ping|done";
		}

		if(data.startsWith("user")) {
			final boolean name = split.length > 1 && split[1].length() > 0;
			final boolean mail = split.length > 2 && split[2].length() > 0;
			final boolean pass = split.length > 3 && split[3].length() > 0;

			if(name) {
				if(split[1].length() < 3)
					return "user|fail|name too short";

				if(split[1].length() > 12)
					return "user|fail|name too long";

				if(name && !split[1].matches("[a-zA-Z0-9.\\-]+"))
					return "user|fail|name invalid";

				if(name && split[1].matches("[0-9]+"))
					return "user|fail|name alpha missing"; // [0-9]+ reserved for <id>
			}

			if(mail && split[2].indexOf("@") < 1 && !split[2].matches("[a-zA-Z0-9.@\\-\\+]+"))
				return "user|fail|mail invalid";

			if(pass && split[3].length() < 3)
				return "user|fail|pass too short";

			Async.Work user = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					String json = "{";
					String sort = "";

					boolean add = false;

					if(name) {
						json += "\"name\":\"" + split[1].toLowerCase() + "\"";
						sort += ",name";

						add = true;
					}

					if(mail) {
						if(add)
							json += ",";

						json += "\"mail\":\"" + split[2].toLowerCase() + "\"";
						sort += ",mail";

						add = true;
					}

					if(pass) {
						if(add)
							json += ",";

						json += "\"pass\":\"" + split[3] + "\"";
					}

					json += "}";

					byte[] post = ("json=" + json + "&sort=key" + sort + "&create").getBytes("utf-8");
					String host = event.query().header("host");

					call.post("/node", "Host:" + host, post);
				}

				public void read(String host, String body) throws Exception {
					System.out.println(body);

					if(body.indexOf("Validation") > 0) {
						String message = body.substring(body.indexOf("[") + 1, body.indexOf("]"));

						System.out.println("Validation " + message);

						if(message.startsWith("name"))
							event.query().put("fail", "user|fail|name contains bad characters");

						if(message.startsWith("mail"))
							event.query().put("fail", "user|fail|mail contains bad characters");
					}
					else if(body.indexOf("Collision") > 0) {
						String message = body.substring(body.indexOf("[") + 1, body.indexOf("]"));

						System.out.println("Collision " + message);

						if(message.startsWith("name"))
							event.query().put("fail", "user|fail|name already registered");

						if(message.startsWith("mail"))
							event.query().put("fail", "user|fail|mail already registered");
					}
					else {
						JSONObject json = new JSONObject(body);
						String key = json.getString("key");

						User user = session(event, name ? split[1] : "", session(), json);
						user.sign = true;

						event.query().put("done", "user|done|" + user.salt + "|" + key + "|" + Root.hash(key));
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

		if(data.startsWith("mail") && split[1].contains("@")) {
			File file = new File(Root.home() + "/node/user/mail" + Root.path(split[1].toLowerCase()));

			if(file == null || !file.exists()) {
				return "mail|fail|not found";
			}

			JSONObject json = new JSONObject(Root.file(file));

			return "mail|done|" + (json.has("name") ? json.getString("key") : Root.hash(json.getString("key")));
		}

		if(data.startsWith("salt")) {
			String name = split[1].toLowerCase();
			String salt = session();

			File file = null;
			boolean id = name.matches("[0-9]+");

			if(id)
				file = new File(Root.home() + "/node/user/id" + Root.path(Long.parseLong(name)));
			else
				file = new File(Root.home() + "/node/user/name" + Root.path(name));

			if(file == null || !file.exists()) {
				return "salt|fail|" + (id ? "id" : "name") + " not found";
			}

			JSONObject json = new JSONObject(Root.file(file));

			session(event, name, salt, json);

			return "salt|done|" + salt;
		}

		if(split.length < 2)
			return "main|fail|salt not found";

		//System.out.println(split[1]);
		
		User user = (User) users.get(split[1]);
		
		//System.out.println(user);
		
		if(user == null || !user.salt.equals(split[1]))
			return "main|fail|salt not found";

		if(data.startsWith("sign")) {
			String hash = split[2].toLowerCase();

			if(user.name.length() > 0 && hash.length() > 0) {
				String key = user.name.matches("[0-9]+") || !user.json.has("pass") ? user.json.getString("key") : user.json.getString("pass");
				String md5 = Deploy.hash(key + user.salt, "MD5");

				if(hash.equals(md5)) {
					user.sign = true;
					return "sign|done|" + user.name;
				}
				else {
					users.remove(user.salt);
					return "sign|fail|wrong " + (user.json.has("pass") ? "pass" : "key");
				}
			}
		}

		if(!user.sign)
			return "main|fail|user not authorized";

		if(data.startsWith("game")) {
			if(!split[2].matches("[a-zA-Z]+"))
				return "game|fail|name invalid";

			Game game = (Game) games.get(split[2].toLowerCase());

			if(game == null) {
				game = new Game(split[2].toLowerCase());
				games.put(split[2], game);
			}

			user.game = game;
			user.move(null, game);
			
			//broadcast(user, "here|root|" + user.name, true);

			// add this user and users in other games to each other
			
			Iterator it = users.values().iterator();
			
			while(it.hasNext()) {
				User u = (User) it.next();
				
				if(user.game != null && u.game != null && user.game.name != u.game.name && user.name != u.name) {
					node.push(u.salt, "here|root|" + user.name, true);
					node.push(user.salt, "here|root|" + u.name, true);
				}
			}
			
			// add this user to users in same game but in rooms
			
			it = user.game.rooms.values().iterator();
			
			while(it.hasNext()) {
				Room r = (Room) it.next();
				r.send(user, "here|stem|" + user.name, true);
			}
			
			return "game|done";
		}

		if(user.game == null)
			return "main|fail|no game";

		if(data.startsWith("name") || data.startsWith("nick") || data.startsWith("pass") || data.startsWith("mail")) {
			File file = null;
			final String rule = split[0];
			boolean id = split[2].matches("[0-9]+");

			if(id) {
				file = new File(Root.home() + "/node/user/id" + Root.path(Long.parseLong(split[2])));

				if(file == null || !file.exists()) {
					return rule + "|fail|not found";
				}

				JSONObject json = new JSONObject(Root.file(file));

				try {
					return rule + "|done|" + json.getString(rule);
				}
				catch(JSONException e) {
					return rule + "|fail|not found";
				}
			}
			else {
				if(rule.equals("mail") && split[2].indexOf("@") < 1 && !split[2].matches("[a-zA-Z0-9.@\\-\\+]+"))
					return "mail|fail|mail invalid";

				if((rule.equals("name") || rule.equals("nick")) && !split[2].matches("[a-zA-Z0-9.\\-]+"))
					return rule + "|fail|" + rule + " invalid";

				if(rule.equals("name") && split[2].matches("[0-9]+"))
					return "name|fail|name alpha missing"; // [0-9]+ reserved for <id>

				if(rule.equals("name"))
					user.json.put("name", split[2].toLowerCase());
				else if(rule.equals("nick"))
					user.json.put("nick", split[2]);
				else if(rule.equals("pass"))
					user.json.put("pass", split[2]);
				else
					user.json.put("mail", split[2]);

				final String json = user.json.toString();

				Async.Work update = new Async.Work(event) {
					public void send(Async.Call call) throws Exception {
						String sort = "";

						if(rule.equals("name"))
							sort = "&sort=name";

						byte[] post = ("json=" + json + sort).getBytes("utf-8");
						String host = event.query().header("host");
						call.post("/node", "Host:" + host, post);
					}

					public void read(String host, String body) throws Exception {
						System.out.println(body);

						if(body.indexOf("Collision") > 0) {
							String message = body.substring(body.indexOf("[") + 1, body.indexOf("]"));

							System.out.println("Collision " + message);

							if(message.startsWith("name"))
								event.query().put("fail", "name|fail|taken");
						}
						else
							event.query().put("done", rule + "|done");

						event.reply().wakeup();
					}

					public void fail(String host, Exception e) throws Exception {
						e.printStackTrace();
					}
				};

				event.daemon().client().send("localhost", update, 30);
				return "hold";
			}
		}

		if(data.startsWith("away")) {
			if(!user.room.away() && user.room.play)
				user.room.send(user, "hold", true);

			user.away = true;
			user.room.send(user, "away|" + user.name);

			return "away|done";
		}

		if(data.startsWith("back")) {
			user.away = false;
			user.room.send(user, "back|" + user.name);

			if(!user.room.away() && user.room.play)
				user.room.send(user, "free", true);

			return "back|done";
		}

		if(data.startsWith("ally")) {
			File file = null;
			String name = split[2];
			boolean id = name.matches("[0-9]+");

			if(id)
				file = new File(Root.home() + "/node/user/id" + Root.path(Long.parseLong(name)));
			else
				file = new File(Root.home() + "/node/user/name" + Root.path(name));

			if(file == null || !file.exists()) {
				return "ally|fail|" + (id ? "id" : "name") + " not found";
			}

			final JSONObject json = new JSONObject(Root.file(file));
			final User stencil = user;
			
			Async.Work link = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					call.post("/link", "Host:" + event.query().header("host"), 
							("pkey=" + stencil.json.getString("key") + "&ckey=" + json.getString("key") + 
									"&ptype=user&ctype=user").getBytes("utf-8"));
				}

				public void read(String host, String body) throws Exception {
					System.out.println("fuse ally " + body);
					stencil.add(Root.hash(json.getString("key")));
					event.query().put("done", "ally|done");
					event.reply().wakeup();
				}

				public void fail(String host, Exception e) throws Exception {
					System.out.println("fuse ally " + e);
				}
			};

			event.daemon().client().send("localhost", link, 30);

			return "hold";
		}

		if(data.startsWith("peer")) {
			user.peer(event, split[2]);
			return "peer|done";
		}

		if(data.startsWith("room")) {
			if(user.room.user != null)
				return "room|fail|not in lobby";

			String type = split[2];

			if(!type.matches("[a-zA-Z]+"))
				return "room|fail|type invalid";

			int size = Integer.parseInt(split[3]);

			Room room = new Room(user, type, size);

			user.game.rooms.put(user.name, room);
			user.game.send(user, "room|" + room);
			user.move(user.game, room);

			return "room|done";
		}

		if(data.startsWith("list")) {
			String what = split[2];

			if(what.equals("room")) {
				StringBuilder builder = new StringBuilder("list|done|room|");
				Iterator it = user.game.rooms.values().iterator();

				while(it.hasNext()) {
					Room room = (Room) it.next();
					builder.append(room);
					
					if(it.hasNext()) {
						builder.append(";");
					}
				}

				return builder.toString();
			}

			if(what.equals("data")) {
				final String type = split[3];

				final int from = 0;
				final int size = 5;
				final String key = user.json.getString("key");

				Async.Work work = new Async.Work(event) {
					public void send(Async.Call call) throws Exception {
						call.get("/link/user/" + type + "/" + key + "?from=" + from + 
								"&size=" + size, "Host:" + event.query().header("host"));
					}

					public void read(String host, String body) throws Exception {
						StringBuilder builder = new StringBuilder("list|done|data");
						final JSONObject result = (JSONObject) new JSONObject(body);
						JSONArray list = result.getJSONArray("list");

						for(int i = 0; i < list.length(); i++) {
							JSONObject item = list.getJSONObject(i);
							long id = Root.hash(item.getString("key"));							
							builder.append("|" + id);
						}

						event.query().put("done", builder.toString());
						event.reply().wakeup();
					}

					public void fail(String host, Exception e) throws Exception {
						System.out.println("fuse load " + e);
					}
				};

				event.daemon().client().send("localhost", work, 30);
				return "hold";
			}

			return "list|fail|wrong type";
		}

		if(data.startsWith("join")) {
			Room room = (Room) user.game.rooms.get(split[2]);
			String info = split[3];
			
			if(room == null) {
				boolean game = user.room instanceof Game;
				
				if(!game && user.room.users.size() == user.room.size && !user.room.play)
					return "join|fail|is full";
				
				User poll = (User) names.get(split[2]);

				if(poll != null && user.game.name.equals(poll.game.name)) {
					if(!game)
						return "join|fail|user busy";
					
					node.push(poll.salt, "poll|" + user.name + "|" + info, true);

					poll.poll = user.name;
					user.poll = poll.name;
					
					return "join|done|poll";
				}
			}

			if(room == null)
				return "join|fail|not found";

			if(user.room.user != null && user.room.user.name.equals(room.user.name))
				return "join|fail|already here";

			if(room.users.size() == room.size && !room.play)
				return "join|fail|is full";

			user.move(user.room, room);

			if(room.users.size() == room.size)
				user.game.send(user, "lock|" + user.room.user.name);

			return "join|done|room";
		}

		if(data.startsWith("poll")) {
			User poll = (User) names.get(split[2]);
			boolean accept = split[3].toLowerCase().equals("true");

			if(poll == null)
				return "poll|fail|not found";

			if(user.poll == null || !user.poll.equals(poll.name))
				return "poll|fail|wrong user";

			if(accept) {
				boolean game = poll.room instanceof Game;
				Room room = null;
				
				if(!game)
					room = poll.room;
				else {
					room = new Room(poll, "duel", 2);
					user.game.rooms.put(poll.name, room);
					poll.move(poll.room, room);
				}
				
				user.move(user.game, room);
				
				if(game)
					user.game.send(user, "room|" + room);
			}

			user.poll = null;
			
			if(poll.name.equals(user.poll))
				poll.poll = null;
			
			return "poll|done";
		}

		if(data.startsWith("play")) {
			String seed = split[2];

			if(user.room.away())
				return "play|fail|someone is away";

			if(user.room.play)
				return "play|fail|already playing";

			if(user.room.user == null)
				return "play|fail|in lobby";

			if(user.room.users.size() < 2)
				return "play|fail|only one player";

			if(user.room.user == user)
				user.room.send(user, "play|" + seed, true);
			else
				return "play|fail|not creator";

			user.game.send(user, "view|" + user.room.user.name);

			return "play|done";
		}

		if(data.startsWith("over")) {
			if(!user.room.play)
				return "over|fail|not playing";

			if(split.length > 2)
				user.room.send(user, "over|" + user.name + '|' + split[2], true);
			else
				user.room.send(user, "over|" + user.name, true);

			user.lost++;

			Iterator it = user.room.users.values().iterator();

			while(it.hasNext()) {
				User other = (User) it.next();

				if(user.salt != other.salt) {
					StringBuilder builder = new StringBuilder();

					if(user.flag != null)
						builder.append("<img style=\"display: inline;\" src=\"flag/" + user.flag + ".gif\">&nbsp;");

					builder.append("<font color=\"#ff3300\">" + user.name + "(" + user.lost + ")</font> vs. ");

					if(other.flag != null)
						builder.append("<img style=\"display: inline;\" src=\"flag/" + other.flag + ".gif\">&nbsp;");

					builder.append("<font color=\"#00cc33\">" + other.name + "</font>");

					score.add(builder.toString());

					if(score.size() > 10) {
						score.poll();
					}
				}
			}

			return "over|done";
		}

		if(data.startsWith("quit")) {
			if(user.room.user == null)
				return "quit|fail|in lobby";

			Room room = user.room;
			boolean full = room.users.size() == room.size;
			Room drop = user.move(user.room, user.game);

			if(drop != null) {
				user.game.rooms.remove(drop.user.name);
				user.game.send(user, "drop|" + drop.user.name);
			}
			else if(full)
				user.game.send(user, "open|" + room.user.name);

			return "quit|done";
		}

		if(data.startsWith("save")) {
			if(split[2].length() > 512) {
				return "save|fail|too large";
			}

			final String type = split[1];
			final JSONObject json = new JSONObject(split[3]);
			final String key = json.optString("key");
			final String user_key = user.json.getString("key");

			Async.Work node = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					String data = "json=" + json.toString() + "&type=" + type + 
							"&sort=key" + (key.length() > 0 ? "" : "&create");
					call.post("/node", "Host:" + event.query().header("host"), data.getBytes("utf-8"));
				}

				public void read(String host, final String body) throws Exception {
					final JSONObject node = (JSONObject) new JSONObject(body);

					Async.Work link = new Async.Work(event) {
						public void send(Async.Call call) throws Exception {
							call.post("/link", "Host:" + event.query().header("host"), 
									("pkey=" + user_key + "&ckey=" + node.getString("key") + 
											"&ptype=user&ctype=" + type).getBytes("utf-8"));
						}

						public void read(String host, String body) throws Exception {
							System.out.println("fuse node " + body);
							String key = node.getString("key");
							event.query().put("done", "save|done|" + Root.hash(key) + "|" + key);
							event.reply().wakeup();
						}

						public void fail(String host, Exception e) throws Exception {
							System.out.println("fuse link " + e);
						}
					};

					event.daemon().client().send(host, link, 30);
				}

				public void fail(String host, Exception e) throws Exception {
					System.out.println("fuse save " + e);
				}
			};

			event.daemon().client().send("localhost", node, 30);
			return "hold";
		}

		if(data.startsWith("load")) {
			String type = split[1];
			long id = Long.parseLong(split[3]);

			File file = new File(Root.home() + "/node/" + type + "/id" + Root.path(id));

			if(!file.exists()) {
				System.out.println(file);
				event.query().put("fail", "load|fail|not found");
			}

			return "load|done|" + Root.file(file);
		}

		if(data.startsWith("chat")) {
			String tree = split[2];
			boolean game = user.room instanceof Game;
			
			if(split.length > 3) {
				if(tree.equals("root"))
					broadcast(user, "chat|root|" + user.name + "|" + split[3], true);
			
				if(tree.equals("root") || tree.equals("stem"))
					user.game.send(user, "chat|stem|" + user.name + "|" + split[3], true);
			
				if((tree.equals("root") || tree.equals("stem") || tree.equals("leaf")) && !game)
					user.room.send(user, "chat|leaf|" + user.name + "|" + split[3], true);
			}
			
			return "chat|done";
		}

		if(data.startsWith("send")) {
			user.room.send(user, "send|" + user.name + "|" + split[2]);
			return "send|done";
		}

		if(data.startsWith("move")) {
			user.room.send(user, "move|" + user.name + "|" + split[2]);
			return "move|done";
		}

		return "main|fail|type not found";
	}

	public static class User {
		String[] ip;
		JSONObject json;
		LinkedList ally = new LinkedList();
		String name, salt, nick, flag, poll;
		boolean sign, away;
		Game game;
		Room room;
		int lost;
		long id;

		User(String name, String salt, JSONObject json) throws Exception {
			this.name = name;
			this.salt = salt;
			this.json = json;
			this.id = Root.hash(json.getString("key"));

			try {
				ally();
			}
			catch(Exception e) {
				e.printStackTrace();
			}
		}

		private void ally() throws Exception {
			File file = new File(Root.home() + "/link/user/user" + Root.path(json.getString("key")));

			if(!file.exists())
				return;

			RandomAccessFile raf = new RandomAccessFile(file, "rw");

			int length = (int) raf.length();
			byte[] data = new byte[length];
			int read = raf.read(data);
			ByteBuffer buffer = ByteBuffer.wrap(data); // TODO: add 0, read?

			for(int i = 0; i < length / 8; i++) {
				if(buffer.remaining() > 0)
					ally.addFirst(new Long(buffer.getLong()));
				else
					break;
			}

			raf.close();
		}

		void peer(Event event, String ip) {
			this.ip = new String[2];
			this.ip[0] = ip;
			this.ip[1] = event.remote();
		}

		void add(long ally) {
			this.ally.add(new Long(ally));
		}

		boolean ally(User user) {
			return ally.contains(new Long(user.id));
		}

		String peer(User other) {
			if(ip != null)
				if(other.ip != null && ip[1].equals(other.ip[1]))
					return "|" + ip[0];
				else
					return "|" + ip[1];

			return "";
		}

		Room move(Room from, Room to) throws Exception {
			Room drop = null;
			boolean game = to instanceof Game;
			
			if(to != null) {
				this.room = to;

				to.add(this);
				to.send(this, "here|" + (game ? "stem" : "leaf") + "|" + name);
			}

			if(from != null) {
				from.remove(this);

				if(from.user != null && from.user.name.equals(name)) {
					from.send(this, "stop|" + name);
					from.clear();

					drop = from;
				}
				else
					from.send(this, "gone|" + (game ? "leaf" : "stem") + "|" + name);
			}

			lost = 0;

			return drop;
		}

		public String toString() {
			return name + "(" + salt + ")";
		}
	}

	public static class Room {
		ConcurrentHashMap users = new ConcurrentHashMap();

		boolean play;
		String type;
		User user;
		int size;

		Room(String type, int size) {
			this(null, type, size);
		}

		Room(User user, String type, int size) {
			this.user = user;
			this.type = type;
			this.size = size;
		}

		boolean away() {
			Iterator it = users.values().iterator();

			while(it.hasNext()) {
				User user = (User) it.next();

				if(user.away)
					return true;
			}

			return false;
		}

		void send(User from, String data) throws Exception {
			send(from, data, false);
		}

		void send(User from, String data, boolean all) throws Exception {
			Iterator it = users.values().iterator();

			if(data.startsWith("play"))
				play = true;

			if(data.startsWith("over"))
				play = false;

			if(!data.startsWith("send") && !data.startsWith("move"))
				System.err.println("<-- " + from + " " + data);

			if(data.startsWith("here"))
				System.err.println(users);
			
			boolean wakeup = false;

			while(it.hasNext()) {
				User user = (User) it.next();

				try {
					boolean user_game = user.room instanceof Game;
					boolean from_game = from.room instanceof Game;
					
					// send every user in room to joining user
					
					if(data.startsWith("here") && !from.name.equals(user.name)) {
						node.push(from.salt, "here|" + (user_game || from_game ? "stem" : "leaf") + "|" + user.name + user.peer(from), false);

						if(from.ally(user))
							node.push(from.salt, "ally|" + user.name, false);

						if(user.away)
							node.push(from.salt, "away|" + user.name, false);

						wakeup = true;
					}

					// send every user in room to leaving user
					
					if(data.startsWith("gone") && !from.name.equals(user.name)) {
						node.push(from.salt, "gone|" + (from_game ? "stem" : "leaf") + "|" + user.name + user.peer(from), false);
						
						wakeup = true;
					}

					// send message from user to room
					
					if(all || !from.name.equals(user.name)) {
						if(data.startsWith("here")) {
							node.push(user.salt, data + from.peer(user), true);
						}
						else if(data.startsWith("gone") && from.room.user != null) {
							node.push(user.salt, data + "|" + from.room.user.name, true);
						}
						else {
							node.push(user.salt, data, true);
						}
					}

					// eject everyone
					
					if(data.startsWith("stop")) {
						user.move(null, user.game);
					}
				}
				catch(Exception e) {
					e.printStackTrace(); // user timeout?
				}
			}

			if(wakeup)
				node.wakeup(from.salt);

			// broadcast stop
			
			if(data.startsWith("quit")) {
				user.game.send(from, "stop|" + user.name);
			}
		}

		void clear() {
			users.clear();
		}

		void add(User user) {
			users.put(user.name, user);
		}

		void remove(User user) {
			users.remove(user.name);
		}

		public String toString() {
			return (user == null ? "lobby" : user.name) + "," + type + "," + size;
		}
	}

	static void add(Event event, String data, boolean error) {
		String rule = data.substring(0, 4);
		boolean fail = data.substring(5, 9).equals("fail");

		Stat stat = (Stat) stats.get(rule);

		if(stat == null) {
			stat = new Stat();
			stats.put(rule, stat);
		}

		long time = System.currentTimeMillis() - event.big("time");

		if(time > 50) {
			System.out.println(rule + " " + time);
		}

		if(error)
			stat.error++;

		if(fail)
			stat.fail++;

		stat.count++;
		stat.total += time;
		stat.min = (int) (time < stat.min ? time : stat.min);
		stat.max = (int) (time > stat.max ? time : stat.max);
	}

	public static class Warn extends Service {
		public String path() { return "/warn"; }
		public void filter(Event event) throws Event, Exception {
			event.query().parse();
			String text = event.string("text");
			String secret = event.string("secret");
			
			if(secret.equals("1234")) {
				node.broadcast("warn|none|" + text, false);
				event.output().print("OK");
			}
			else
				event.output().print("KO");
		}
	}
	
	public static class Stat {
		long total;
		int count;
		int error;
		int fail;
		int min = Integer.MAX_VALUE;
		int max;
	}

	public static class Data extends Service {
		static DecimalFormat decimal;

		static {
			decimal = (DecimalFormat) DecimalFormat.getInstance();
			decimal.applyPattern("#.#");
		}

		public String path() { return "/data"; }
		public void filter(Event event) throws Event, Exception {
			Iterator it = stats.keySet().iterator();
			Output out = event.output();

			event.query().parse();

			if(event.bit("clear")) {
				stats = new ConcurrentHashMap();
				out.println("Stats cleared!");
				throw event;
			}

			out.println("<pre>");
			out.println("<table>");
			out.println("<tr><td>rule&nbsp;</td><td>avg.&nbsp;</td><td>min.&nbsp;</td><td>max.&nbsp;</td><td></td><td>num.&nbsp;</td><td>fail&nbsp;</td><td>err.&nbsp;</td></tr>");
			out.println("<tr><td colspan=\"8\" bgcolor=\"#000000\"></td></tr>");

			while(it.hasNext()) {
				String name = (String) it.next();
				Stat stat = (Stat) stats.get(name);

				float avg = (float) stat.total / (float) stat.count;

				out.println("<tr><td>" + name + "&nbsp;&nbsp;&nbsp;</td><td>" + decimal.format(avg) + "&nbsp;&nbsp;&nbsp;</td><td>" + stat.min + "&nbsp;&nbsp;&nbsp;</td><td>" + stat.max + "&nbsp;&nbsp;&nbsp;</td><td>x</td><td>" + stat.count + "&nbsp;&nbsp;&nbsp;</td><td>" + stat.fail + "&nbsp;&nbsp;&nbsp;</td><td>" + stat.error + "&nbsp;&nbsp;&nbsp;</td></tr>");
			}

			out.println("</table>");
			out.println("</pre>");
		}
	}

	public static class Game extends Room {
		ConcurrentHashMap rooms = new ConcurrentHashMap();
		String name;

		public Game(String name) {
			super(null, "game", 1024);
			this.name = name;
		}
	}

	public void broadcast(User user, String message, boolean ignore) throws Exception {
		Iterator it = users.values().iterator();
		
		while(it.hasNext()) {
			User u = (User) it.next();
			
			boolean add = true;
			
			if(ignore)
				add = (u.game == null || user.game == null || !u.game.name.equals(user.game.name));
			
			if(add)
				node.push(u.salt, message, true);
		}
	}
	
	static protected String stack(Thread thread) {
		StackTraceElement[] stack = thread.getStackTrace();
		StringBuilder builder = new StringBuilder();

		for(int i = 0; i < stack.length; i++) {
			builder.append(stack[i]);

			if(i < stack.length - 1) {
				builder.append("\r\n");
			}
		}

		return builder.toString();
	}
	
	public synchronized void remove(String salt, int place) throws Exception {
		User user = (User) users.get(salt);

		//System.err.println("exit " + place + " " + user + " " + stack(Thread.currentThread()));
		
		if(user != null && user.salt != null && user.game != null) {
			users.remove(salt);
			
			Room room = user.move(user.room, null);
			user.game.rooms.remove(user.name);

			if(place != 1) {
				user.game.send(user, "exit|" + user.name);
			}
			
			names.remove(user.name);
			broadcast(user, "gone|root|" + user.name, false);
		}
	}

	public void exit() {
		//daemon.remove(this);
	}
}