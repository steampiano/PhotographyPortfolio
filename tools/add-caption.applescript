-- Pick the photo
try
	set imgFile to choose file with prompt "Select the photo to caption:" default location (POSIX file "/Users/kev/Claude/photography-portfolio/photos")
on error number -128
	return
end try

set imgPath to POSIX path of imgFile
set AppleScript's text item delimiters to "."
set pathParts to text items of imgPath
if (count of pathParts) > 1 then
	set basePath to (items 1 thru -2 of pathParts) as text
else
	set basePath to imgPath
end if
set AppleScript's text item delimiters to ""
set txtPath to basePath & ".txt"

-- Open a larger, resizable Quick Look preview of the photo so it's clearly
-- visible while you fill in the fields below. Closed automatically when this
-- app finishes (or if you cancel partway through).
set qlPID to do shell script "qlmanage -p " & quoted form of imgPath & " > /dev/null 2>&1 & echo $!"

-- People (handles, comma + space separated). The @ is added automatically.
try
	set rawPeople to text returned of (display dialog "People featured (handles, separated by \", \"):" & return & "e.g. alice, bob  (the @ is added for you)" default answer "" with title "Add Photo Info")
on error number -128
	my closeQuickLook(qlPID)
	return
end try

-- Normalize each handle to have exactly one leading @.
set thePeople to ""
if rawPeople is not "" then
	set AppleScript's text item delimiters to ","
	set handleItems to text items of rawPeople
	set AppleScript's text item delimiters to ""
	set normed to {}
	repeat with h in handleItems
		set hTrim to my trimText(h as text)
		if hTrim is not "" then
			repeat while hTrim starts with "@"
				if (count of hTrim) > 1 then
					set hTrim to text 2 thru -1 of hTrim
				else
					set hTrim to ""
				end if
			end repeat
			if hTrim is not "" then set end of normed to "@" & hTrim
		end if
	end repeat
	set AppleScript's text item delimiters to ", "
	set thePeople to normed as text
	set AppleScript's text item delimiters to ""
end if

-- Caption (optional)
try
	set theCaption to text returned of (display dialog "Caption (optional):" default answer "" with title "Add Photo Info")
on error number -128
	my closeQuickLook(qlPID)
	return
end try

-- Event (optional)
try
	set theEvent to text returned of (display dialog "Event (leave blank if none):" default answer "" with title "Add Photo Info")
on error number -128
	my closeQuickLook(qlPID)
	return
end try

-- Featured?
try
	set featuredChoice to button returned of (display dialog "Feature this photo in the carousel?" buttons {"Cancel", "No", "Yes"} default button "No" with title "Add Photo Info")
on error number -128
	my closeQuickLook(qlPID)
	return
end try
if featuredChoice is "Cancel" then
	my closeQuickLook(qlPID)
	return
end if

-- Build the file contents: metadata block (if any), blank line, then caption
set metaLines to ""
if theEvent is not "" then set metaLines to metaLines & "event: " & theEvent & linefeed
if featuredChoice is "Yes" then
	set metaLines to metaLines & "featured: yes" & linefeed
else
	set metaLines to metaLines & "featured: no" & linefeed
end if
if thePeople is not "" then set metaLines to metaLines & "people: " & thePeople & linefeed

if metaLines is not "" and theCaption is not "" then
	set fileText to metaLines & linefeed & theCaption
else if metaLines is not "" then
	set fileText to metaLines
else
	set fileText to theCaption
end if

set fileRef to open for access (POSIX file txtPath) with write permission
set eof of fileRef to 0
write fileText to fileRef as «class utf8»
close access fileRef

my closeQuickLook(qlPID)
display notification "Saved photo info" with title "Add Photo Info"

-- Close the Quick Look preview window opened for this photo.
on closeQuickLook(pid)
	try
		do shell script "kill " & pid & " 2>/dev/null"
	end try
end closeQuickLook

-- Trim leading/trailing spaces and tabs from a string.
on trimText(t)
	set chars to {" ", tab}
	repeat while (count of t) > 0 and (character 1 of t) is in chars
		if (count of t) > 1 then
			set t to text 2 thru -1 of t
		else
			set t to ""
		end if
	end repeat
	repeat while (count of t) > 0 and (character -1 of t) is in chars
		if (count of t) > 1 then
			set t to text 1 thru -2 of t
		else
			set t to ""
		end if
	end repeat
	return t
end trimText
