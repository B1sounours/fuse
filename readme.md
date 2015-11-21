<pre>
<img width="140" height="80" src="https://rawgit.com/tinspin/fuse/master/res/fuse.svg">

+---------------------------+
| <i>The multiplayer solution.</i> |
+---------------------------+

Support:

  - unity
    - plugin is only one file of C# code: <a href="https://github.com/tinspin/fuse/blob/master/src/Fuse.cs">Fuse.cs</a>
  - javascript XHR/XDR, 99.9% of browsers, only IE7 missing
    - CORS compliant, static hosting: <a href="https://github.com/tinspin/fuse/blob/master/res/game.html">game.html</a>
  - java will be added later, if somebody needs it now just post an issue.
  - 100% firewall pass-through
  - all gameplay types:
    - from two player turn-based
    - to real-time action MMO

Protocol:

  - client/server triplex HTTP, upstream \n terminated or 'data: \n\n' encapsulated
    - dynamic presence position move packets for MMO
  - peer-to-peer UDP, binary physics packets for VR
    - position move and talk, look, head, body, hand packets
  - multicast UDP on cluster for load distribution

Platform:

  - proven for 5 years
  - 100% uptime on routing
  - 100% read uptime on data

Examples:

  - javascript block-drop game: <a href="http://fuse.rupy.se">cube</a> (open-source, try single-player <a href="http://fuse.rupy.se/play.html">play.html</a>)
  - java 3D VR MMO space shooter: <a href="http://aeonalpha.com">aeon</a> (closed-source)

+-------------------+
| <i>Work in progress!</i> |
+-------------------+

o-> = async. broadcast to Read() (C#) or read(data) (XHR/XDR) including self
x-> = async. broadcast to Read() (C#) or read(data) (XHR/XDR) excluding self
 -> = sync. return on Push(data) or push(data)

< > = mandatory
[ ] = optional

 *  = not implemented yet

In sort of chronological order:

+-----------------------------------+
| <i>Rule</i>                      -> <i>Echo</i> |
+-----------------------------------+

                            // register
                            // [name] if you can't store the &lt;id&gt; otherwise set
                            //        to empty string (||)
                            // [mail] if your users want recovery otherwise set 
                            //        to empty string (||)
                            // [pass] if you can't store the &lt;key&gt; otherwise set
                            //        to empty string (||)
                            //        preferably [pass] is a hash with salt 
                            //        we simply use md5(pass + name.toLowerCase())
 <b><i>user</i></b>|[name]|[mail]|[pass]  -> user|done|&lt;salt&gt;|&lt;key&gt;|&lt;id&gt;
                            -> user|fail|name too short
                            -> user|fail|name too long
                            -> user|fail|name already registered
                            -> user|fail|name invalid       // [a-zA-Z0-9.\\-]+
                            -> user|fail|name alpha missing // [0-9]+ reserved for &lt;id&gt;
                            -> user|fail|mail invalid       // [a-zA-Z0-9.@\\-\\+]+
                            -> user|fail|mail already registered
                            -> user|fail|pass too short
 
                            // to get the &lt;name&gt;/&lt;id&gt; of a mail
                            // if you want to login with &lt;name&gt;/&lt;id&gt; below
 <b><i>mail</i></b>|&lt;mail&gt;                -> mail|done|&lt;name&gt;/&lt;id&gt;
                            -> mail|fail|not found
 
                            // get salt for &lt;name&gt; or &lt;id&gt;
 <b><i>salt</i></b>|&lt;name&gt;/&lt;id&gt;           -> salt|done|&lt;salt&gt;
                            -> salt|fail|name not found
                            -> salt|fail|id not found
 
 <b><i>\/</i></b> anything below          -> main|fail|salt not found
 
                            // login
                            // &lt;hash&gt; is either md5(&lt;key&gt; + &lt;salt&gt;)
                            //               or md5([pass] + &lt;salt&gt;)
                            //        we use md5(md5(pass + name.toLowerCase()) + &lt;salt&gt;)
                            //        make sure you keep the case correct
 <b><i>sign</i></b>|&lt;salt&gt;|&lt;hash&gt;         -> sign|done|&lt;name&gt;/&lt;id&gt;
                            -> sign|fail|wrong pass
                            -> sign|fail|wrong key

 <b><i>\/</i></b> anything below          -> main|fail|not authorized

+-------------------------------+
| <i>Here you have to call pull()!</i> |
+-------------------------------+

+------------------------------------------------------+
| <i>Below this line &lt;name&gt;/&lt;id&gt; is replaced with &lt;user&gt;</i>. |
+------------------------------------------------------+

                            // join a game
 <b><i>game</i></b>|&lt;salt&gt;|&lt;name&gt;         -> game|done
                           x-> <b><i>here</i></b>|&lt;user&gt;[|&lt;ip&gt;]
                           x-> <b><i>ally</i></b>|&lt;user&gt;
                           o-> <b><i>self</b></i>|&lt;user&gt;|&lt;data&gt;           // if avatar set
                           o-> <b><i>name</b></i>|&lt;user&gt;|&lt;name&gt;           // if &lt;id&gt; used and name set
                           o-> <b><i>nick</b></i>|&lt;user&gt;|&lt;nick&gt;           // if &lt;id&gt; used and nick set
                           o-> <b><i>flag</b></i>|&lt;user&gt;|&lt;flag&gt;           // country
                            -> game|fail|name invalid       // [a-zA-Z]+

 <b><i>\/</i></b> anything below          -> main|fail|no game

                            // set nick for user
 <b><i>nick</i></b>|&lt;salt&gt;|&lt;nick&gt;         -> nick|done
                            -> nick|fail|nick invalid       // [a-zA-Z0-9.\\-]+

                            // get nick for any id
 <b><i>nick</i></b>|&lt;salt&gt;|&lt;id&gt;           -> nick|done|&lt;nick&gt;
                            -> nick|fail|not found

                            // set name for user
 <b><i>name</i></b>|&lt;salt&gt;|&lt;name&gt;         -> name|done
                            -> name|fail|name invalid       // [a-zA-Z0-9.\\-]+
                            -> name|fail|name alpha missing // [0-9]+ reserved for &lt;id&gt;
                            -> name|fail|taken

                            // get name for any id
 <b><i>name</i></b>|&lt;salt&gt;|&lt;id&gt;           -> name|done|&lt;name&gt;
                            -> name|fail|not found

                            // set pass
 <b><i>pass</i></b>|&lt;salt&gt;|&lt;pass&gt;         -> pass|done
 
                            // set mail
 <b><i>mail</i></b>|&lt;salt&gt;|&lt;mail&gt;         -> pass|done
                            -> mail|fail|mail invalid       // [a-zA-Z0-9.@\\-\\+]+

                            // pause game
 <b><i>away</i></b>|&lt;salt&gt;|&lt;bool&gt;         -> away|done
                           x-> <b><i>away</b></i>|&lt;user&gt;
                           x-> <b><i>back</b></i>|&lt;user&gt;

                            // add/remove friend
 <b><i>ally</i></b>|&lt;salt&gt;|&lt;user&gt;         -> ally|done|&lt;bool&gt;
                            -> ally|fail|name not found
                            -> ally|fail|id not found

                            // set avatar
*<b><i>self</i></b>|&lt;salt&gt;|&lt;data&gt;         -> self|done
                           x-> <b><i>self</b></i>|&lt;user&gt;|&lt;data&gt;

                            // enable peer-to-peer
 <b><i>peer</i></b>|&lt;salt&gt;|&lt;ip&gt;           -> peer|done                    // send the internal IP

                            // host room
 <b><i>room</i></b>|&lt;salt&gt;|&lt;type&gt;|&lt;size&gt;  -> room|done
                           x-> <b><i>room</i></b>|&lt;user&gt;+&lt;type&gt;+&lt;size&gt;
                            -> room|fail|not in lobby
                            -> room|fail|type invalid       // [a-zA-Z]+

                            // list rooms or data
 <b><i>list</i></b>|&lt;salt&gt;|room           -> list|done|room|&lt;user&gt;+&lt;type&gt;+&lt;size&gt;|...
 <b><i>list</i></b>|&lt;salt&gt;|data|&lt;type&gt;    -> list|done|data|&lt;id&gt;|...      // use load to get data
                            -> list|fail|wrong type

                            // join room
                            // between <i>lock</i> and <i>view</i> nobody can join
 <b><i>join</i></b>|&lt;salt&gt;|&lt;user&gt;         -> join|done
                           x-> <b><i>here</i></b>|&lt;user&gt;[|&lt;ip&gt;]           // in new room
                           x-> <b><i>ally</i></b>|&lt;user&gt;
                           x-> <b><i>gone</i></b>|&lt;user&gt;|&lt;room&gt;           // in lobby
                           x-> <b><i>lock</i></b>|&lt;room&gt;                  // in lobby if room is full
                            -> join|fail|not found
                            -> join|fail|already here
                            -> join|fail|is full

                            // permanently ban user from room
*<b><i>kick</i></b>|&lt;salt&gt;|&lt;user&gt;         -> kick|done
                            -> kick|fail|not creator
                            -> kick|fail|not here
 
                            // quit room
 <b><i>quit</i></b>|&lt;salt&gt;                -> quit|done
                           x-> <b><i>here</i></b>|&lt;user&gt;[|&lt;ip&gt;]           // in lobby
                           x-> <b><i>ally</i></b>|&lt;user&gt;
                           x-> <b><i>gone</i></b>|&lt;user&gt;                  // in old room
                           x-> <b><i>drop</i></b>|&lt;user&gt;                  // in lobby if creator leaves
                           x-> <b><i>stop</i></b>|&lt;user&gt;                  // in old room if creator leaves
                           x-> <b><i>open</i></b>|&lt;room&gt;                  // in lobby if room is not full
                            -> quit|fail|in lobby

                            // user exit from platform
 <b><i>exit</i></b>|&lt;salt&gt;                -> exit|done
                           x-> <b><i>exit</i></b>|&lt;user&gt;
                            -> exit|fail|in lobby

                            // save data
 <b><i>save</i></b>|&lt;salt&gt;|&lt;type&gt;|&lt;json&gt;  -> save|done|&lt;id&gt;|&lt;key&gt;         // use key to update
                            -> save|fail|too large

                            // load data
 <b><i>load</i></b>|&lt;salt&gt;|&lt;type&gt;|&lt;id&gt;    -> load|done|&lt;json&gt;             // use &lt;id&gt; from list|data|&lt;type&gt;
                            -> load|fail|not found

                            // play game
 <b><i>play</i></b>|&lt;salt&gt;[|seed]         -> play|done
                           o-> <b><i>play</i></b>[|seed]                  // to start the game
                           x-> <b><i>view</i></b>|&lt;room&gt;                  // in lobby if room has started
                            -> play|fail|in lobby
                            -> play|fail|not creator
                            -> play|fail|only one player
                            -> play|fail|already playing
                            -> play|fail|someone is away

                            // game over
 <b><i>over</i></b>|&lt;salt&gt;[|data]         -> over|done                    // insecure, only for development
                           o-> <b><i>over</b></i>|&lt;user&gt;[|data]           // the game is over
                            -> over|fail|not playing

+------------------------------------------------------------+
| <i>These have to be sent in a separate thread from rendering.</i> |
+------------------------------------------------------------+

                            // chat in any room
 <b><i>chat</i></b>|&lt;salt&gt;|&lt;text&gt;         -> chat|done                    // @[user] of private destination
                           o-> <b><i>chat</i></b>|&lt;user&gt;|&lt;text&gt;
                            -> chat|fail|not online

                            // send any gameplay data to room
 <b><i>send</i></b>|&lt;salt&gt;|&lt;data&gt;         -> send|done
                           x-> <b><i>send</i></b>|&lt;user&gt;|&lt;data&gt;
 
                            // motion for 3D MMO games with dynamic here/gone
*<b><i>move</i></b>|&lt;salt&gt;|&lt;data&gt;         -> move|done
                           x-> <b><i>move</i></b>|&lt;user&gt;|&lt;data&gt;
                            // &lt;data&gt; = &lt;x&gt;+&lt;y&gt;+&lt;z&gt;|&lt;x&gt;+&lt;y&gt;+&lt;z&gt;+&lt;w&gt;|&lt;action&gt;(|&lt;speed&gt;|...)
                            //          position   |orientation    |key/button

 <b><i>/\</b></i> type not implemented    -> main|fail|type not found

+-----------------+       
| <i>Sketched rules.</i> |
+-----------------+

// peer protocol

*<b><i>talk</i></b> // send voice
*<b><i>look</i></b> // send eye movement
*<b><i>head</i></b> // send head movement
*<b><i>body</i></b> // send body movement
*<b><i>hand</i></b> // send hand movement

// name pool

 cash info ping data
 time host pull head
 pick push hide tail
 show fill full make
 slay ruin rise hold
 drop made halt free
 body text sent

// attribution

 pop.mp3 - Mark DiAngelo
 snap.mp3 - Mark DiAngelo
</pre>