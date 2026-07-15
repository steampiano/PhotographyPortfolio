-- Force-refetches everyone's Instagram avatar (picks up profile picture
-- changes), then commits and pushes ONLY the avatars/ folder. Runs
-- silently and reports the result in a single dialog. No Terminal window.

set shellCmd to "export PATH=/opt/homebrew/bin:/usr/bin:/bin; cd /Users/kev/Claude/photography-portfolio && python3 tools/refresh_avatars.py && git add avatars && { if git diff --cached --quiet; then echo NOCHANGES; else git commit -m \"Refresh avatars ($(date '+%Y-%m-%d %H:%M'))\" >/dev/null 2>&1 && git push && echo PUBLISHED; fi; }"

try
	set shellOut to do shell script shellCmd
	if shellOut contains "NOCHANGES" then
		display dialog "No avatar changes to publish." & return & "Everyone's cached picture matched what's on Instagram already." buttons {"OK"} default button "OK" with title "Refresh Avatars"
	else
		display dialog "Avatars refreshed!" & return & return & "The site updates in about a minute at aspy.pics." buttons {"OK"} default button "OK" with title "Refresh Avatars"
	end if
on error errMsg
	display dialog "Refresh failed:" & return & return & errMsg buttons {"OK"} default button "OK" with title "Refresh Avatars" with icon caution
end try
