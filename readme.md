<pre>
+---------------------------+
| <i>The multiplayer solution.</i> |
+---------------------------+

Support:

  - unity
    - plugin is only 140 lines of C# code: <a href="https://github.com/tinspin/fuse/blob/master/src/Fuse.cs">Fuse.cs</a>
  - javascript XHR/XDR, 99.9% of browsers, only IE7 missing
    - CORS compliant, static hosting: <a href="https://github.com/tinspin/fuse/blob/master/res/game.html">game.html</a>
  - java will be added later, if somebody needs it now just post an issue.
  - 100% firewall pass-through
  - all gameplay types:
    - from two player turn-based
    - to real-time action MMO

Protocol:

  - client/server triplex HTTP, \n terminated or 'data: \n\n' encapsulated
    - dynamic presence position move packets for MMO
  - peer-to-peer UDP, binary physics packets for VR
    - position move and talk, look, head, body, hand packets
  - multicast UDP on cluster for load distribution

Platform:

  - proven for 5 years
  - 100% uptime on routing
  - 100% read uptime on data

Examples:

    <img src="https://rawgit.com/tinspin/fuse/master/res/svg/blue.svg">&nbsp;<img src="https://rawgit.com/tinspin/fuse/master/res/svg/green.svg">&nbsp;<img src="https://rawgit.com/tinspin/fuse/master/res/svg/orange.svg">&nbsp;<img src="https://rawgit.com/tinspin/fuse/master/res/svg/purple.svg">
  - javascript block-drop game: <a href="http://fuse.rupy.se">cube</a> (open-source <a href="https://github.com/tinspin/fuse/blob/master/res/play.html">play.html</a>, try single-player <a href="http://fuse.rupy.se/play.html">play.html</a>)
  
    <img src="https://dl.dropboxusercontent.com/u/1352420/aeon_alpha.png">
  - java 3D VR MMO space shooter: <a href="http://aeonalpha.com">aeon</a> (closed source)

+-------------------+
| <i>Work in progress!</i> |
+-------------------+

--> = async. broadcast to Read() (C#) or read(data) (XHR/XDR)
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
                            //        for example we simply use md5(pass + name)
 <b><i>user</i></b>|[name]|[mail]|[pass]  -> user|done|&lt;salt&gt;|&lt;key&gt;|&lt;id&gt;
                            -> user|fail|name too short
                            -> user|fail|name too long
                            -> user|fail|name already registered
                            -> user|fail|name invalid       // only alphanumeric and .-
                            -> user|fail|name alpha missing // numeric reserved for &lt;id&gt;
                            -> user|fail|mail invalid       // only alphanumeric and .@-+
                            -> user|fail|mail already registered
                            -> user|fail|pass too short
 
                            // to get the &lt;id&gt; of a mail
                            // if you want to login with &lt;id&gt; below
 <b><i>mail</i></b>|&lt;mail&gt;                -> mail|done|&lt;id&gt;
                            -> mail|fail|not found
 
                            // get salt for &lt;name&gt; or &lt;id&gt;
 <b><i>salt</i></b>|&lt;name&gt;/&lt;id&gt;           -> salt|done|&lt;salt&gt;
                            -> salt|fail|not found
 
 <b><i>\/</i></b> anything below          -> main|fail|salt not found
 
                            // login
                            // &lt;hash&gt; is either md5(&lt;key&gt; + &lt;salt&gt;)
                            //               or md5([pass] + &lt;salt&gt;)
                            //        we use md5(md5(pass + name) + &lt;salt&gt;)
                            //        make sure you keep the case correct
 <b><i>hash</i></b>|&lt;salt&gt;|&lt;hash&gt;         -> hash|done|&lt;name&gt;/&lt;id&gt;
                            -> hash|fail|wrong pass
                            -> hash|fail|wrong key

 <b><i>\/</i></b> anything below          -> main|fail|not authorized

+-------------------------------+
| <i>Here you have to call pull()!</i> |
+-------------------------------+

+------------------------------------------------------+
| <i>Below this line &lt;name&gt;/&lt;id&gt; is replaced with &lt;user&gt;</i>. |
+------------------------------------------------------+

                            // join a game
 <b><i>game</i></b>|&lt;salt&gt;|&lt;name&gt;         -> game|done
                           --> <b><i>here</i></b>|&lt;user&gt;[|&lt;ip&gt;]
                           --> <b><i>self</b></i>|&lt;user&gt;|&lt;data&gt;           // if avatar set
                            -> game|fail|name invalid
                     
 <b><i>\/</i></b> anything below          -> main|fail|no game

                            // pause game
*<b><i>away</i></b>|&lt;salt&gt;|&lt;bool&gt;         -> away|done
                           --> <b><i>hold</b></i>|&lt;user&gt;
                           --> <b><i>free</b></i>|&lt;user&gt;

                            // add friend
*<b><i>ally</i></b>|&lt;salt&gt;|&lt;user&gt;         -> ally|done
                            -> ally|fail|not found

                            // set avatar
*<b><i>body</i></b>|&lt;salt&gt;|&lt;data&gt;         -> body|done
                           --> <b><i>self</b></i>|&lt;user&gt;|&lt;data&gt;

                            // enable peer-to-peer
 <b><i>peer</i></b>|&lt;salt&gt;|&lt;ip&gt;           -> peer|done                    // send the internal IP

                            // host room
 <b><i>room</i></b>|&lt;salt&gt;|&lt;type&gt;|&lt;size&gt;  -> room|done
                           --> <b><i>made</i></b>|&lt;user&gt;+&lt;type&gt;+&lt;size&gt;+&lt;case&gt;
                            -> room|fail|not in lobby
                            -> room|fail|type invalid       // only alpha

                            // list allies, rooms or data
                            // &lt;case&gt; can be <i>join</i>, <i>lock</i> or <i>view</i>
*<b><i>list</i></b>|&lt;salt&gt;|ally           -> list|done|ally|&lt;user&gt;|...
 <b><i>list</i></b>|&lt;salt&gt;|room           -> list|done|room|&lt;user&gt;+&lt;type&gt;+&lt;size&gt;+&lt;case&gt;|...
 <b><i>list</i></b>|&lt;salt&gt;|data|&lt;type&gt;    -> list|done|data|&lt;id&gt;|...      // use load to get data
                            -> list|fail|wrong type

                            // join room
                            // between <i>lock</i> and <i>view</i> nobody can join
 <b><i>join</i></b>|&lt;salt&gt;|&lt;user&gt;         -> join|done
                           --> <b><i>here</i></b>|&lt;user&gt;[|&lt;ip&gt;]           // in new room
                           --> <b><i>gone</i></b>|&lt;user&gt;|&lt;room&gt;           // in lobby
                           --> <b><i>lock</i></b>|&lt;room&gt;                  // in lobby if room is full
                           --> <b><i>open</i></b>|&lt;room&gt;                  // in lobby if room is not full
                            -> join|fail|not found
                            -> join|fail|already here
                            -> join|fail|is full

                            // permanently ban user from room
*<b><i>kick</i></b>|&lt;salt&gt;|&lt;user&gt;         -> kick|done
                            -> kick|fail|not creator
                            -> kick|fail|not here
 
                            // quit room
 <b><i>quit</i></b>|&lt;salt&gt;                -> quit|done
                           --> <b><i>here</i></b>|&lt;user&gt;[|&lt;ip&gt;]           // in lobby
                           --> <b><i>halt</i></b>|&lt;user&gt;                  // in lobby if creator leaves
                           --> <b><i>gone</i></b>|&lt;user&gt;                  // in old room
                           --> <b><i>stop</i></b>|&lt;user&gt;                  // in old room if creator leaves
                            -> room|fail|in lobby

                            // user exit
 <b><i>exit</i></b>|&lt;salt&gt;                -> exit|done
                           --> <b><i>kill</i></b>|&lt;user&gt;
                            -> exit|fail|in lobby

                            // insert data
 <b><i>save</i></b>|&lt;salt&gt;|&lt;type&gt;|&lt;json&gt;  -> save|done|&lt;id&gt;|&lt;key&gt;         // use key to update
                            -> save|fail|too large

                            // select data
 <b><i>load</i></b>|&lt;salt&gt;|&lt;type&gt;|&lt;id&gt;    -> load|done|&lt;json&gt;             // use &lt;id&gt; from list|data|&lt;type&gt;
                            -> load|fail|not found

                            // start game
 <b><i>play</i></b>|&lt;salt&gt;[|seed]         -> play|done
                           --> <b><i>head</i></b>[|seed]                  // to start the game
                           --> <b><i>view</i></b>|&lt;room&gt;                  // in lobby if room has started
                            -> play|fail|in lobby
                            -> play|fail|not creator
                            -> play|fail|only one player

                            // game over
 <b><i>over</i></b>|&lt;salt&gt;[|data]         -> over|done                    // insecure, only for development
                           --> <b><i>tail</b></i>|&lt;user&gt;[|data]           // the game is over

+------------------------------------------------------------+
| <i>These have to be sent in a separate thread from rendering.</i> |
+------------------------------------------------------------+

                            // chat in any room
 <b><i>chat</i></b>|&lt;salt&gt;|&lt;text&gt;         -> chat|done                    // @[user] of private destination
                           --> <b><i>text</i></b>|&lt;user&gt;|&lt;text&gt;
                            -> chat|fail|user not online

                            // send any gameplay data to room
 <b><i>send</i></b>|&lt;salt&gt;|&lt;data&gt;         -> send|done
                           --> <b><i>sent</i></b>|&lt;user&gt;|&lt;data&gt;
 
                            // motion for 3D MMO games with dynamic here/gone
*<b><i>move</i></b>|&lt;salt&gt;|&lt;data&gt;         -> move|done
                           --> <b><i>data</i></b>|&lt;user&gt;|&lt;data&gt;
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

 <b><i>info</i></b>, <b><i>ping</i></b>, <b><i>time</i></b>, <b><i>host</i></b>, <b><i>pull</i></b>, <b><i>pick</i></b>, <b><i>push</i></b>, <b><i>hide</i></b>, <b><i>show</i></b>, <b><i>nick</i></b>, <b><i>fill</i></b>, <b><i>full</i></b>
</pre>