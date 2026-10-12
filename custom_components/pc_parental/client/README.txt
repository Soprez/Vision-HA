VISION — Windows agent
======================

Install this folder on every PC you want Vision to look after. Windows 10 or 11.

Other languages: LISEZ-MOI.txt (Français), LIESMICH.txt (Deutsch),
LEEME.txt (Español), LEGGIMI.txt (Italiano), LEESMIJ.txt (Nederlands).
The agent, the installer and the uninstaller speak the language of Windows
("langues" folder; English when the language is not there).


INSTALLATION (once per PC)
--------------------------
The easiest way: Home Assistant hosts the installer. On the PC to set up,
open PowerShell and paste the line shown in the "Vision" dashboard,
"Install a PC" section. It looks like:

    irm http://YOUR-HA:8123/api/pc_parental/client/install -OutFile "$env:TEMP\install-cp.ps1"; & "$env:TEMP\install-cp.ps1"

Otherwise, with the files in this folder:

1. Copy this whole folder to the PC (desktop, USB stick, anywhere).

2. Right-click  install.ps1  ->  "Run with PowerShell".
   Windows asks for administrator permission: accept.
   (If Windows refuses to run the script, open PowerShell as
    administrator, go to this folder, and first type:
        Set-ExecutionPolicy -Scope Process Bypass
    then  .\install.ps1 )

Either way, the installer asks for two things:
     - the Home Assistant address    e.g. http://homeassistant.local:8123
     - the enrollment key            shown in Home Assistant,
                                     "Vision" dashboard,
                                     "Install a PC" section.

That's it. The PC shows up in Home Assistant by itself, ALLOWED and
   WITH NO TIME SLOT. You then give it its schedule from Home Assistant.

The same key works for every PC.


SENDING A MESSAGE
-----------------
From the "Vision" dashboard, PC card, "Send a message" section: type your
text in the Message field and press Send. A Windows notification appears
on the PC, signed "Vision", and stays in the notification center.

The recipient can be all PCs, a label, or one PC. Labels are set in the
"Labels" field of each card: put "followed" on several PCs and you reach
them all at once, the way you would group smart plugs.

You can pick a sound: default, message, mail, reminder, SMS, insistent
alarm, insistent call, or silent. The two insistent sounds repeat as
long as the notification is shown.

For an automation:

    action: pc_parental.notify
    data:
      tag: followed
      message: Take out the trash
      son: Rappel

Without pc or tag, the message goes to every PC. The "urgent" option opens
a dialog box on top of everything instead of a quiet notification.
Each PC also has its own notify entity, which Home Assistant labels can
target if you prefer that mechanism.


UPDATES
-------
Home Assistant hosts the agent: its copy is the reference. When a new
version arrives with the integration, each PC fetches it by itself,
checks its fingerprint, replaces its script and restarts. Nothing to
reinstall, no file to copy. The texts in every language come inside the
agent.

If you would rather keep control: turn off "Automatic agent updates" in
the dashboard. Each PC then shows its version next to the published one,
with an Install button.

NOTE: automatic updates only exist from agent version 1.1.0 on. A PC still
on 1.0.0 cannot update itself: run the installer once more on that
machine. It is safe, the PC keeps its identity and its time slots.


WHAT THE AGENT DOES
-------------------
- It tells Home Assistant whether someone is at the screen: active,
  idle (nothing touched for a while), session locked, or offline.
  Home Assistant shows it like a messaging status.
- It shows the messages sent from Home Assistant as real Windows
  notifications, signed "Vision" and kept in the notification center.
  A message marked urgent opens a dialog box on top of everything
  instead, which closes by itself.
- Every 20 seconds, it asks Home Assistant whether this PC is allowed
  or blocked.
- During a blocked time slot: the session is locked. If the person
  unlocks it with their password, it is locked again within a second.
  Nothing is lost: programs stay open behind it.
- Before a block, a message warns on screen.
- Software on the "Blocked software" list is closed continuously.
- Sites on the "Blocked sites" list are blocked through the hosts file.

If Home Assistant can't be reached, the last known instruction is kept
for 2 hours, then the PC is left allowed: a server outage must not lock
the computer for good.


MAKING IT EFFECTIVE
-------------------
The agent runs as the SYSTEM account: a user with a standard account can
neither stop it nor uninstall it. However, if the user is an
ADMINISTRATOR of the PC, they can get around it. Check the account:

    Settings > Accounts > Other users

and switch it to "Standard user" if needed.


REMOVING A PC
-------------
From Home Assistant: "Delete this PC" button at the bottom of the PC card,
with confirmation. The PC, its time slots and its device disappear.

The agent stays installed on the machine: it notices it is no longer
known, applies no restriction anymore and leaves the PC allowed. To remove
it for good, run uninstall.ps1 on that machine.


UNINSTALLING
------------
Right-click  uninstall.ps1  ->  "Run with PowerShell", as administrator.
It removes the scheduled task, cleans the hosts file and erases the
configuration. The PC stays visible in Home Assistant: delete it from the
dashboard if you no longer use it.


IF SOMETHING GOES WRONG
-----------------------
The agent log:            C:\ProgramData\HAParental\agent.log
The scheduled task:       Task Scheduler > "HA Controle Parental"
