## Video thumbnail
- In both coil/sketch, the Fetcher/Decoder steps are separate.
- Sketch cannot deal with partial requests (the default HTTP fetcher throws for a non-200 response, when partial request would return 204)
- Therefore I have no choice but to resort to doing my own thumbnail generation directly
- I have demonstrated a POC where I can serve a file via a ktor server that supports range request towards a samba share, and the appropriate ffmpeg command to query that video that also makes the appropriate range requests.
- My next step is to possibly interface with the JNI so that I can call ffmpeg directly. This is needed because previous ffmpeg library helpers in java seem to be archived now.