-- Force-refetches everyone's Instagram avatar (picks up profile picture
-- changes), then commits and pushes ONLY the avatars/ folder. Runs
-- silently and reports the result in a single dialog. No Terminal window.
--
-- If a previous run committed but failed to push (e.g. expired GitHub
-- sign-in), the next run detects the un-pushed commit and pushes it.

set shellCmd to "export PATH=/opt/homebrew/bin:/usr/bin:/bin; cd /Users/kev/Claude/photography-portfolio && python3 tools/refresh_avatars.py && git add avatars && { git diff --cached --quiet || git commit -m \"Refresh avatars ($(date '+%Y-%m-%d %H:%M'))\"; } && { if [ \"$(git rev-list --count @{u}..HEAD 2>/dev/null || echo 0)\" -gt 0 ]; then git push && echo PUBLISHED; else echo NOCHANGES; fi; }"

try
	set shellOut to do shell script shellCmd
	if shellOut contains "NOCHANGES" then
		display dialog "No avatar changes to publish." & return & "Everyone's cached picture matched what's on Instagram already." buttons {"OK"} default button "OK" with title "Refresh Avatars"
	else
		display dialog "Avatars refreshed!" & return & return & "The site updates in about a minute at aspy.pics." buttons {"OK"} default button "OK" with title "Refresh Avatars"
	end if
on error errMsg
	if errMsg contains "could not read Username" or errMsg contains "Authentication failed" or errMsg contains "Device not configured" then
		display dialog "Refresh failed: GitHub sign-in needed." & return & return & "Any changes were saved locally but couldn't be pushed. Sign in to GitHub once (run 'gh auth login', or push from the Terminal so macOS stores your token), then run this again." buttons {"OK"} default button "OK" with title "Refresh Avatars" with icon caution
	else
		display dialog "Refresh failed:" & return & return & errMsg buttons {"OK"} default button "OK" with title "Refresh Avatars" with icon caution
	end if
end try
