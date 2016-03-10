package fuse;

import java.io.File;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import se.rupy.http.Async;
import se.rupy.http.Daemon;
import se.rupy.http.Event;
import se.rupy.http.Output;
import se.rupy.http.Root;
import se.rupy.http.Service;

public class Router implements Node {
	public static boolean wake = true;
	public static boolean queue = true;
	public static boolean debug = true;
	public static int timeout = 5;

	public static String hash = "sha-256";
	public static String host = "root.rupy.se";
	public static String fuse = "fuse.rupy.se";
	public static String path = "fuse.rupy.se";
	public static String what = "localhost";

	public static ConcurrentLinkedDeque score = new ConcurrentLinkedDeque();

	ConcurrentHashMap users = new ConcurrentHashMap();
	ConcurrentHashMap names = new ConcurrentHashMap();
	ConcurrentHashMap games = new ConcurrentHashMap();

	static ConcurrentHashMap stats = new ConcurrentHashMap();
	static Daemon daemon;
	static Node node;

	private static String head() {
		//System.out.println(host);
		return "Head:less\r\nHost:" + host; // Head:less\r\n
	}

	public void call(Daemon daemon, Node node) throws Exception {
		this.daemon = daemon;
		this.node = node;
	}

	private synchronized String session() throws Exception {
		String salt = Event.random(4);

		while(users.get(salt) != null)
			salt = Event.random(4);

		return salt;
	}

	private User session(Event event, String name, String salt) throws Exception {
		User user = new User(name, salt);
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

	public int wakeup(String name) { return 0; }

	static SimpleDateFormat date = new SimpleDateFormat("HH:mm:ss.SSS");

	public void salt(Event event, final String name) throws Exception {
		//System.err.println("salt " + event.index());

		Async.Work work = new Async.Work(event) {
			public void send(Async.Call call) throws Exception {
				call.get("/salt", head());
			}

			public void read(String host, String body) throws Exception {
				if(debug)
					System.err.println(body);
				if(users.containsKey(body)) {
					salt(event, name);
				}
				else {
					session(event, name, body);
					event.query().put("done", "salt|done|" + body);
					int wakeup = event.reply().wakeup(wake, queue);
					if(debug)
						System.err.println(wakeup);
				}
			}

			public void fail(String host, Exception e) throws Exception {
				e.printStackTrace();
				event.query().put("done", "salt|fail|unknown problem");
				event.reply().wakeup(wake, queue);
			}
		};

		event.daemon().client().send(what, work, timeout);
	}

	public String push(final Event event, String data) throws Event, Exception {
		final String[] split = data.split("\\|");

		if(!split[0].equals("send") && !split[0].equals("move")) {
			if(debug)
				System.err.println(" -> '" + data + "'");
		}

		if(split[0].equals("ping")) {
			return "ping|done";
		}

		if(split[0].equals("user")) {
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

			Async.Work work = new Async.Work(event) {
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

					call.post("/node", head(), post);
				}

				public void read(String host, String body) throws Exception {
					if(debug)
						System.err.println(body);

					if(body.indexOf("Validation") > 0) {
						String message = body.substring(body.indexOf("[") + 1, body.indexOf("]"));

						if(debug)
							System.err.println("Validation " + message);

						if(message.startsWith("name"))
							event.query().put("fail", "user|fail|name contains bad characters");

						if(message.startsWith("mail"))
							event.query().put("fail", "user|fail|mail contains bad characters");
					}
					else if(body.indexOf("Collision") > 0) {
						String message = body.substring(body.indexOf("[") + 1, body.indexOf("]"));

						if(debug)
							System.err.println("Collision " + message);

						if(message.startsWith("name"))
							event.query().put("fail", "user|fail|name already registered");

						if(message.startsWith("mail"))
							event.query().put("fail", "user|fail|mail already registered");
					}
					else {
						JSONObject json = new JSONObject(body);
						String key = json.getString("key");

						User user = session(event, name ? split[1] : "", session());
						user.auth(json);

						event.query().put("done", "user|done|" + user.salt + "|" + key + "|" + Root.hash(key));
					}

					event.reply().wakeup(wake, queue);
				}

				public void fail(String host, Exception e) throws Exception {
					e.printStackTrace();
					event.query().put("fail", "user|fail|unknown problem");
					event.reply().wakeup(wake, queue);
				}
			};

			event.daemon().client().send(what, work, timeout);
			throw event;
		}

		if(split[0].equals("salt")) {
			if(split.length < 2)
				return "salt|fail|name not found";

			final String name = split[1].toLowerCase();

			salt(event, name);

			throw event;
		}

		if(split.length < 2)
			return "main|fail|salt not found";

		final User user = (User) users.get(split[1]);

		if(user == null || !user.salt.equals(split[1])) {
			if(debug) {
				System.err.println(split[1]);
				System.err.println(user);
			}
			return "main|fail|salt not found";
		}

		if(split[0].equals("sign")) {
			final String hash = split[2].toLowerCase();

			Async.Work work = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					String body = "name=" + user.name + "&pass=" + hash + "&salt=" + user.salt + "&host=" + host + "&algo=" + Router.hash;
					call.post("/user", head(), body.getBytes());
				}

				public void read(String host, String body) throws Exception {
					if(debug)
						System.err.println(body);
					try {
						JSONObject json = new JSONObject(body);
						user.auth(json);

						event.query().put("done", "sign|done|" + user.name);
					}
					catch(Exception e) {
						event.query().put("fail", "sign|fail|" + body);
					}
					int wakeup = event.reply().wakeup(wake, queue);
					if(debug)
						System.err.println(wakeup);
				}

				public void fail(String host, Exception e) throws Exception {
					e.printStackTrace();
					event.query().put("fail", "sign|fail|unknown problem");
					event.reply().wakeup(wake, queue);
				}
			};

			event.daemon().client().send(what, work, timeout);
			throw event;
		}

		if(!user.sign)
			return "main|fail|user not authorized";

		if(split[0].equals("game")) {
			if(debug)
				System.err.println("push " + Router.date.format(new Date()) + " " + user.salt + " " + user.name);

			if(!split[2].matches("[a-zA-Z]+"))
				return "game|fail|name invalid";

			Game game = (Game) games.get(split[2].toLowerCase());

			if(game == null) {
				game = new Game(split[2].toLowerCase());
				games.put(split[2], game);
			}

			user.game = game;
			user.move(null, game, true);

			// add this user and users in other games to each other

			Iterator it = users.values().iterator();

			while(it.hasNext()) {
				User u = (User) it.next();

				if(user.game != null && u.game != null && user.game.name != u.game.name && user.name != u.name) {
					node.push(u.salt, "here|root|" + user.name, true);
					node.push(user.salt, "here|root|" + u.name, false);
				}
			}

			node.wakeup(user.salt);

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

		if(split[0].equals("name") || split[0].equals("nick") || split[0].equals("pass") || split[0].equals("mail")) {
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

				Async.Work work = new Async.Work(event) {
					public void send(Async.Call call) throws Exception {
						String sort = "";

						if(rule.equals("name"))
							sort = "&sort=name";

						byte[] post = ("json=" + json + sort).getBytes("utf-8");

						call.post("/node", head(), post);
					}

					public void read(String host, String body) throws Exception {
						if(debug)
							System.err.println(body);

						if(body.indexOf("Collision") > 0) {
							String message = body.substring(body.indexOf("[") + 1, body.indexOf("]"));

							if(debug)
								System.err.println("Collision " + message);

							if(message.startsWith("name"))
								event.query().put("fail", "name|fail|taken");
						}
						else
							event.query().put("done", rule + "|done");

						event.reply().wakeup(wake, queue);
					}

					public void fail(String host, Exception e) throws Exception {
						e.printStackTrace();
						event.query().put("fail", rule + "|fail|unknown problem");
						event.reply().wakeup(wake, queue);
					}
				};

				event.daemon().client().send(what, work, timeout);
				throw event;
			}
		}
		else if(split[0].equals("away")) {
			if(!user.room.away() && user.room.play)
				user.room.send(user, "hold", true);

			user.away = true;
			user.room.send(user, "away|" + user.name);

			return "away|done";
		}
		else if(split[0].equals("back")) {
			user.away = false;
			user.room.send(user, "back|" + user.name);

			if(!user.room.away() && user.room.play)
				user.room.send(user, "free", true);

			return "back|done";
		}
		else if(split[0].equals("ally")) {
			String info = split.length > 3 ? "|" + split[3] : "";

			final User poll = (User) names.get(split[2]);

			if(poll == null) {
				return "ally|fail|user not online";
			}

			if(user.ally(poll)) {
				Async.Work work = new Async.Work(event) {
					public void send(Async.Call call) throws Exception {
						call.post("/meta", head(), 
								("pkey=" + user.json.getString("key") + "&ckey=" + poll.json.getString("key") + 
										"&ptype=user&ctype=user&json={}&tear=true").getBytes("utf-8"));
					}

					public void read(String host, String body) throws Exception {
						if(debug)
							System.err.println("fuse ally tear read " + body);
						if(body.equals("1")) {
							user.remove(poll.name);
							poll.remove(user.name);
						}
						event.query().put("done", "sign|done");
						event.reply().wakeup(wake, queue);
					}

					public void fail(String host, Exception e) throws Exception {
						if(debug)
							System.err.println("fuse ally tear fail " + e);
						event.query().put("fail", "sign|fail|unknown problem");
						event.reply().wakeup(wake, queue);
					}
				};

				event.daemon().client().send(what, work, timeout);
				throw event;
			}

			boolean game = user.room instanceof Game;

			if(!game)
				return "ally|fail|user busy";

			node.push(poll.salt, "poll|ally|" + user.name + info, true);

			poll.poll = user.name;
			user.poll = poll.name;
			user.type = poll.type = "ally";

			return "ally|done|poll";
		}
		else if(split[0].equals("peer")) {
			user.peer(event, split[2]);
			return "peer|done";
		}
		else if(split[0].equals("room")) {
			if(user.room.user != null)
				return "room|fail|not in lobby";

			String type = split[2];

			if(!type.matches("[a-zA-Z]+"))
				return "room|fail|type invalid";

			int size = Integer.parseInt(split[3]);

			Room room = new Room(user, type, size);

			user.game.rooms.put(user.name, room);
			user.game.send(user, "room|" + room);
			user.move(user.game, room, true);

			return "room|done";
		}
		else if(split[0].equals("list")) {
			final String list = split[2];
			String type = null;

			if(split.length > 3)
				type = split[3];

			if(list.equals("room")) {
				if(type == null) {
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
				else {
					StringBuilder builder = new StringBuilder("list|done|room|item|");
					Iterator it = user.room.items.values().iterator();
					boolean first = true;

					while(it.hasNext()) {
						Item item = (Item) it.next();

						if(first) {
							first = false;
						}
						else {
							builder.append(";");
						}

						builder.append(item);
					}

					return builder.toString();
				}
			}

			if(list.equals("data") || list.equals("item")) {
				final String key = user.json.getString("key");

				Async.Work work = new Async.Work(event) {
					public void send(Async.Call call) throws Exception {
						call.get("/meta/user/" + list + "/" + user.name, head());
					}

					public void read(String host, String body) throws Exception {
						if(debug)
							System.err.println(body);
						try {
							user.item = (JSONObject) new JSONObject(body);
							JSONArray l = user.item.getJSONArray("list");

							StringBuilder builder = new StringBuilder("list|done|" + list + "|");

							for(int i = 0; i < l.length(); i++) {
								JSONObject json = l.getJSONObject(i);
								String[] name = JSONObject.getNames(json);
								JSONObject item = json.getJSONObject(name[0]);

								if(list.equals("data"))
									builder.append(name[0] + "," + item.toString().length());
								else
									builder.append(name[0] + "," + item.optInt("count"));

								if(i < l.length() - 1)
									builder.append(";");
							}

							event.query().put("done", builder.toString());
						}
						catch(Exception e) {
							event.query().put("fail", "list|fail|not found");
						}

						event.reply().wakeup(wake, queue);
					}

					public void fail(String host, Exception e) throws Exception {
						if(debug)
							System.err.println("list data " + e);
						event.query().put("fail", "list|fail|unknown problem");
						event.reply().wakeup(wake, queue);
					}
				};

				event.daemon().client().send(what, work, timeout);
				throw event;
			}

			return "list|fail|wrong type";
		}
		else if(split[0].equals("join")) {
			Room room = (Room) user.game.rooms.get(split[2]);
			String info = split[3];

			if(room == null) {
				boolean game = user.room instanceof Game;

				if(!game && user.room.users.size() == user.room.size && !user.room.play)
					return "join|fail|is full";

				User poll = (User) names.get(split[2]);

				boolean poll_game = poll.room instanceof Game;
				
				if(poll != null && user.game.name.equals(poll.game.name)) {
					if(!game || !poll_game)
						return "join|fail|user busy";

					node.push(poll.salt, "poll|join|" + user.name + "|" + info, true);

					poll.poll = user.name;
					user.poll = poll.name;
					user.type = poll.type = "join";

					return "join|done|poll";
				}
			}

			if(room == null)
				return "join|fail|not found";

			if(user.room.user != null && user.room.user.name.equals(room.user.name))
				return "join|fail|already here";

			if(room.users.size() == room.size && !room.play)
				return "join|fail|is full";

			user.move(user.room, room, true);

			if(room.users.size() == room.size)
				user.game.send(user, "lock|" + user.room.user.name);

			return "join|done|room";
		}
		else if(split[0].equals("poll")) {
			String type = user.type;

			if(debug)
				System.err.println(split[2] + " " + names);

			final User poll = (User) names.get(split[2]);
			boolean accept = split[3].toLowerCase().equals("true");

			if(poll == null)
				return "poll|fail|user not online";

			if(user.poll == null || !user.poll.equals(poll.name))
				return "poll|fail|wrong user";

			if(accept) {
				if(type.equals("join")) {
					boolean game = poll.room instanceof Game;
					Room room = null;

					if(!game)
						room = poll.room;
					else {
						room = new Room(poll, "duel", 2);
						user.game.rooms.put(poll.name, room);
						poll.move(poll.room, room, false);
					}

					user.move(user.game, room, true);

					if(game)
						user.game.send(user, "room|" + room);
				}
				else if(type.equals("ally")) {
					Async.Work work = new Async.Work(event) {
						public void send(Async.Call call) throws Exception {
							call.post("/meta", head(), 
									("pkey=" + user.json.getString("key") + "&ckey=" + poll.json.getString("key") + 
											"&ptype=user&ctype=user&json={}&echo=true").getBytes("utf-8"));
						}

						public void read(String host, String body) throws Exception {
							if(debug)
								System.err.println("fuse ally read " + body);
							if(body.equals("1")) {
								user.add(poll.name);
								poll.add(user.name);
							}
							event.query().put("done", "ally|done");
							event.reply().wakeup(wake, queue);
						}

						public void fail(String host, Exception e) throws Exception {
							if(debug)
								System.err.println("fuse ally fail " + e);
							event.query().put("fail", "ally|fail|unknown problem");
							event.reply().wakeup(wake, queue);
						}
					};

					event.daemon().client().send(what, work, timeout);
					throw event;
				}
				else
					return "poll|fail|type not found";
			}

			user.poll = null;
			poll.poll = null;
			user.type = poll.type = null;

			return "poll|done|" + type;
		}
		else if(split[0].equals("play")) {
			String seed = split[2];

			if(user.room.away())
				return "play|fail|someone is away";

			if(user.room.play)
				return "play|fail|already playing";

			if(user.room.user == null)
				return "play|fail|in lobby";

			if(user.room.users.size() < 2 && !user.room.type.equals("item"))
				return "play|fail|only one player";

			if(user.room.user == user)
				user.room.send(user, "play|" + seed, true);
			else
				return "play|fail|not creator";

			user.game.send(user, "view|" + user.room.user.name);

			return "play|done";
		}
		else if(split[0].equals("over")) {
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
		else if(split[0].equals("exit")) {
			if(user.room.user == null)
				return "exit|fail|in lobby";

			Room room = user.room;
			boolean full = room.users.size() == room.size;
			Room drop = user.move(user.room, user.game, false);

			if(drop != null) {
				user.game.rooms.remove(drop.user.name);
				user.game.send(user, "drop|" + drop.user.name);
			}
			else if(full)
				user.game.send(user, "open|" + room.user.name);

			user.game.wakeup();

			return "exit|done";
		}
		else if(split[0].equals("drop")) {
			final String name = split[2];
			int many = Integer.parseInt(split[3]);

			JSONObject json = user.item(name);

			if(json == null)
				return "drop|fail|not found";

			int count = json.optInt("count");

			if(count < many)
				return "drop|fail|not enough";

			final Item item = user.room.item(user, name, many);
			json.put("count", count - many);
			final String save = json.toString();

			Async.Work work = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					call.post("/meta", head(), 
							("pkey=" + user.json.getString("key") + "&ckey=" + name + 
									"&ptype=user&ctype=item&json=" + save).getBytes("utf-8"));
				}

				public void read(String host, String body) throws Exception {
					if(debug)
						System.err.println(split[0] + " " + body);
					user.room.send(user, "item|" + item, true);
					event.query().put("done", split[0] + "|done|" + item.salt);
					event.reply().wakeup(wake, queue);
				}

				public void fail(String host, Exception e) throws Exception {
					if(debug)
						System.err.println(split[0] + " " + e);
					event.query().put("fail", split[0] + "|fail|unknown problem");
					event.reply().wakeup(wake, queue);
				}
			};

			event.daemon().client().send(what, work, timeout);
			throw event;
		}
		else if(split[0].equals("pick")) {
			String salt = split[2];

			final Item item = user.room.item(salt);

			if(item == null)
				return "pick|fail|not found";

			JSONObject json = user.item(item.name);

			if(json == null)
				json = new JSONObject("{count: " + item.count + "}");
			else
				json.put("count", json.optInt("count") + item.count);

			final String save = json.toString();

			Async.Work work = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					call.post("/meta", head(), 
							("pkey=" + user.json.getString("key") + "&ckey=" + item.name + 
									"&ptype=user&ctype=item&json=" + save).getBytes("utf-8"));
				}

				public void read(String host, String body) throws Exception {
					if(debug)
						System.err.println(split[0] + " " + body);
					user.room.send(user, "pick|" + user.name + "|" + item.salt, true);
					event.query().put("done", split[0] + "|done|" + item.salt);
					event.reply().wakeup(wake, queue);
				}

				public void fail(String host, Exception e) throws Exception {
					if(debug)
						System.err.println(split[0] + " " + e);
					event.query().put("fail", split[0] + "|fail|unknown problem");
					event.reply().wakeup(wake, queue);
				}
			};

			event.daemon().client().send(what, work, timeout);
			throw event;
		}
		else if(split[0].equals("save") || split[0].equals("tear")) {
			final boolean tear = split[0].equals("tear");

			if(split[2].length() < 3) {
				return split[0] + "|fail|name too short";
			}

			if(split[2].length() > 12) {
				return split[0] + "|fail|name too long";
			}

			final String name = split[2];
			final JSONObject json = tear ? null : new JSONObject(split[3]);
			final String type = tear ? split[3] : split.length > 4 ? split[4] : "data";

			Async.Work work = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					call.post("/meta", head(), 
							("pkey=" + user.json.getString("key") + "&ckey=" + name + 
									"&ptype=user&ctype=" + type + (tear ? "&tear=true" : "&json=" + json)).getBytes("utf-8"));
				}

				public void read(String host, String body) throws Exception {
					if(debug)
						System.err.println(split[0] + " " + body);
					JSONObject data = user.item(name);
					if(data != null)
						data = json;
					event.query().put("done", split[0] + "|done");
					event.reply().wakeup(wake, queue);
				}

				public void fail(String host, Exception e) throws Exception {
					if(debug)
						System.err.println(split[0] + " " + e);
					event.query().put("fail", split[0] + "|fail|unknown problem");
					event.reply().wakeup(wake, queue);
				}
			};

			event.daemon().client().send(what, work, timeout);
			throw event;
		}
		else if(split[0].equals("load") || split[0].equals("data") || split[0].equals("item")) {
			boolean load = split[0].equals("load");
			final String base = load ? user.name : split[2];
			final String name = load ? split[2] : split[3];
			final String type = load && split.length > 3 ? split[3] : split[0];

			Async.Work work = new Async.Work(event) {
				public void send(Async.Call call) throws Exception {
					call.get("/meta/user/" + type + "/" + base + "/" + URLEncoder.encode(name, "UTF-8"), head());
				}

				public void read(String host, String body) throws Exception {
					try {
						JSONObject json = new JSONObject(body);
						event.query().put("done", split[0] + "|done|" + body);
					}
					catch(Exception e) {
						event.query().put("fail", split[0] + "|fail|" + body);
					}

					event.reply().wakeup(wake, queue);
				}

				public void fail(String host, Exception e) throws Exception {
					e.printStackTrace();
					event.query().put("fail", split[0] + "|fail|unknown problem");
					event.reply().wakeup(wake, queue);
				}
			};

			event.daemon().client().send(what, work, timeout);
			throw event;
		}
		else if(split[0].equals("chat")) {
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
		else if(split[0].equals("send")) {
			user.room.send(user, "send|" + user.name + "|" + split[2]);
			return "send|done";
		}
		else if(split[0].equals("move")) {
			user.room.send(user, "move|" + user.name + "|" + split[2]);
			String[] pos = split[2].split(";")[0].split(",");
			user.x = Float.parseFloat(pos[0]);
			user.y = Float.parseFloat(pos[1]);
			user.z = Float.parseFloat(pos[2]);
			return "move|done";
		}

		return "main|fail|rule not found";
	}

	public static class Part {
		String salt;
		String name;
		float x, y, z;
	}

	public static class Item extends Part {
		int count;

		public Item(String name, int count) {
			this.name = name;
			this.count = count;
		}

		public void position(User user) {
			this.x = user.x;
			this.y = user.y;
			this.z = user.z;
		}

		public String toString() {
			return salt + "," + x + "," + y + "," + z + "," + name + "," + count;
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

	public static class User extends Part {
		String[] ip;
		JSONObject json;
		JSONObject item;
		LinkedList ally = new LinkedList();
		String nick, flag, poll, type;
		boolean sign, away;
		Game game;
		Room room;
		int lost;
		long id;

		User(String name, String salt) throws Exception {
			this.name = name;
			this.salt = salt;
		}

		private void auth(JSONObject json) throws Exception {
			this.json = json;
			this.id = Root.hash(json.getString("key"));
			this.sign = true;

			try {
				ally();
			}
			catch(Exception e) {
				e.printStackTrace();
			}
		}

		JSONObject item(String name) throws Exception {
			JSONArray list = item.getJSONArray("list");

			for(int i = 0; i < list.length(); i++) {
				JSONObject item = list.getJSONObject(i);
				String[] names = JSONObject.getNames(item);

				if(names[0].equals(name)) {
					return item.getJSONObject(names[0]);
				}
			}

			return null;
		}

		private void ally() throws Exception {
			Async.Work work = new Async.Work(null) {
				public void send(Async.Call call) throws Exception {
					call.get("/meta/user/user/" + json.getString("key"), head());
				}

				public void read(String host, String body) throws Exception {
					try {
						JSONObject json = new JSONObject(body);
						JSONArray list = json.getJSONArray("list");

						for(int i = 0; i < list.length(); i++) {
							String name = JSONObject.getNames(list.getJSONObject(i))[0];
							ally.addFirst(name);
						}
					}
					catch(JSONException e) {}
				}

				public void fail(String host, Exception e) throws Exception {
					e.printStackTrace();
				}
			};

			daemon.client().send(what, work, timeout);
		}

		void peer(Event event, String ip) {
			this.ip = new String[2];
			this.ip[0] = ip;
			this.ip[1] = event.remote();
		}

		void add(String ally) {
			this.ally.add(ally);
		}

		void remove(String ally) {
			this.ally.remove(ally);
		}

		boolean ally(User user) {
			return ally.contains(user.name);
		}

		String peer(User other) {
			if(ip != null)
				if(other.ip != null && ip[1].equals(other.ip[1]))
					return "|" + ip[0];
				else
					return "|" + ip[1];

			return "";
		}

		Room move(Room from, Room to, boolean wakeup) throws Exception {
			Room drop = null;
			boolean to_game = to instanceof Game;
			boolean from_game = from instanceof Game;

			if(to != null) {
				this.room = to;

				to.add(this);
				to.send(this, "here|" + (to_game ? "stem" : "leaf") + "|" + name);
			}

			if(from != null) {
				from.remove(this);

				if(from.user != null && from.user.name.equals(name)) {
					from.send(this, "stop|" + name);
					from.clear();

					drop = from;
				}
				else
					from.send(this, "gone|" + (from_game ? "stem": "leaf") + "|" + name);
			}

			if(wakeup) {
				if(to != null)
					to.wakeup();

				if(from != null)
					from.wakeup();
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
		ConcurrentHashMap items = new ConcurrentHashMap();

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

		private synchronized Item item(User user, String name, int count) throws Exception {
			String salt = Event.random(4);

			while(items.get(salt) != null)
				salt = Event.random(4);

			Item item = new Item(name, count);
			item.position(user);
			item.salt = salt;

			items.put(salt, item);

			return item;
		}

		private synchronized Item item(String salt) {
			return (Item) items.remove(salt);
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

		public void wakeup() throws Exception {
			Iterator it = users.values().iterator();

			while(it.hasNext()) {
				Part p = (Part) it.next();

				if(p instanceof User) {
					User u = (User) p;
					node.wakeup(u.salt);
				}
			}
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

			//if(!data.startsWith("send") && !data.startsWith("move"))
			//if(debug)
			//System.err.println("<-- " + from + " " + data);

			if(data.startsWith("here"))
				if(debug)
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
					}

					// send every user in room to leaving user

					if(data.startsWith("gone") && !from.name.equals(user.name)) {
						node.push(from.salt, "gone|" + (user_game || from_game ? "stem" : "leaf") + "|" + user.name + user.peer(from), false);
					}

					// send message from user to room

					if(all || !from.name.equals(user.name)) {
						if(data.startsWith("here")) {
							node.push(user.salt, data + from.peer(user), false);

							if(user.ally(from))
								node.push(user.salt, "ally|" + from.name, false);

							//wakeup = true;
						}
						else if(data.startsWith("gone") && from.room.user != null) {
							node.push(user.salt, data + "|" + from.room.user.name, false);

							//wakeup = true;
						}
						else if(data.startsWith("drop")) {
							node.push(user.salt, data, false);
						}
						else {
							node.push(user.salt, data, true);

							//wakeup = true;
						}
					}

					// eject everyone

					if(data.startsWith("stop")) {
						user.move(null, user.game, false);
					}
				}
				catch(Exception e) {
					e.printStackTrace(); // user timeout?
				}

				if(wakeup) {
					int wake = node.wakeup(user.salt);

					if(wake != 0)
						if(debug)
							System.err.println("user wakeup " + wake);
				}
			}

			// broadcast stop

			if(data.startsWith("exit")) {
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
			if(debug)
				System.err.println(rule + " " + time);
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

	public void broadcast(User user, String message, boolean ignore) throws Exception {
		Iterator it = users.values().iterator();

		while(it.hasNext()) {
			User u = (User) it.next();
			String m = message;
			boolean add = true;

			if(ignore)
				add = (u.game == null || user.game == null || !u.game.name.equals(user.game.name));

			if(ignore && !add && u.room.user != null && user.room.user == null) { // fix for stem -> leaf
				add = !u.game.name.equals(u.room.user.name);
				
				if(add && m.startsWith("chat")) {
					String[] split = m.split("|");
					m = "chat|stem|" + split[2] + "|" + split[3];
				}
			}
			
			if(add)
				node.push(u.salt, m, true);
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

		if(debug)
			System.err.println("quit " + place + " " + user + " " + salt); // + " " + stack(Thread.currentThread()));

		users.remove(salt);

		if(user != null && user.salt != null && user.game != null) {
			Room room = user.move(user.room, null, false);
			user.game.rooms.remove(user.name);

			if(place != 1) {
				user.game.send(user, "quit|" + user.name);
			}

			names.remove(user.name);
			broadcast(user, "gone|root|" + user.name, false);
		}
	}

	public void exit() {
		//daemon.remove(this);
	}
}