-- Publishes photo updates: builds thumbnails + posts.json, then commits and
-- pushes ONLY the photo-related files. Runs silently and reports the result in
-- a single dialog. No Terminal window.

set shellCmd to "export PATH=/opt/homebrew/bin:/usr/bin:/bin; cd /Users/kev/Claude/photography-portfolio && python3 build.py && git add photos thumbs previews other-photos thumbs-other previews-other posts.json && { if git diff --cached --quiet; then echo NOCHANGES; else git commit -m \"Update photos ($(date '+%Y-%m-%d %H:%M'))\" >/dev/null 2>&1 && git push && echo PUBLISHED; fi; }"

try
	set shellOut to do shell script shellCmd
	if shellOut contains "NOCHANGES" then
		display dialog "No new photo changes to publish." & return & "Add photos and captions first, then run this again." buttons {"OK"} default button "OK" with title "Publish"
	else
		display dialog "Published!" & return & return & "The site updates in about a minute at aspy.pics." buttons {"OK"} default button "OK" with title "Publish"
	end if
on error errMsg
	display dialog "Publish failed:" & return & return & errMsg buttons {"OK"} default button "OK" with title "Publish" with icon caution
end try
