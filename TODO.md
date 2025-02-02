## Feature Priority
- [x] 0 Intent picker for video at least (VLC target)
- [x] 1 Gallery Browser mode (just swipe left and right)
- [x] 1 Configure remotes
  - [x] Perhaps just show as folder icons when you first load
  - [x] (Remote #n)
  - [x] (Add Remote)
- [ ] 2 Inline search
- [x] 2 Tag search (reading XMP files)
- [ ] 2 Display filter (hide metadata files)
- [ ] 3 Configure size of items / grid size / grid layout
- [ ] 3 Expand gallery folder view to fit all items
- [x] 3 Performance when loading
- [x] 3 Breadcrumbs not working
- [ ] 1 Video support
  - ~~[ ] Support thumbnail via MediaDataSource~~ Possible but uncacheable
  - [x] Support range seeking via Range headers
  - [x] Support playback

## Get in my hands priority
- [x] 0 Figure where I can package my app (Android Studio build APK)
- [ ] 1 Figure how to do this via (Github) CI so I can download / share it

## Usability priority
- Thumbnail generation needs to be async
- I need to do caching myself otherwise I cannot re-use thumbnail images or video thumbnails
- [ ] Add a background job (like what i did for ktor server) that will fetch images and create thumbnails to save
- [ ] Push to the background job whenever the view changes 
- [ ] Find a way to render a thumbnail from a video in <10s