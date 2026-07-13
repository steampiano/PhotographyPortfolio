#!/bin/bash
osascript <<'APPLESCRIPT'
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

-- Caption
try
    set theCaption to text returned of (display dialog "Caption for this photo:" default answer "" with title "Add Photo Info")
on error number -128
    return
end try

-- Event (optional)
try
    set theEvent to text returned of (display dialog "Event (leave blank if none):" default answer "" with title "Add Photo Info")
on error number -128
    return
end try

-- Featured?
try
    set featuredChoice to button returned of (display dialog "Feature this photo in the carousel?" buttons {"Cancel", "No", "Yes"} default button "No" with title "Add Photo Info")
on error number -128
    return
end try
if featuredChoice is "Cancel" then return

-- Build the file contents: metadata block (if any), blank line, then caption
set metaLines to ""
if theEvent is not "" then set metaLines to metaLines & "event: " & theEvent & linefeed
if featuredChoice is "Yes" then set metaLines to metaLines & "featured: yes" & linefeed

if metaLines is not "" then
    set fileText to metaLines & linefeed & theCaption
else
    set fileText to theCaption
end if

set fileRef to open for access (POSIX file txtPath) with write permission
set eof of fileRef to 0
write fileText to fileRef as «class utf8»
close access fileRef

display notification "Saved photo info" with title "Add Photo Info"
APPLESCRIPT
