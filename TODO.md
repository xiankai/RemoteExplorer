## Feature Priority
- [ ] Video playback needs to work when seeking
- [ ] Status bar to show the current state of thumbnails / tags / etc

## Get in my hands priority
- [ ] Figure out how to get my app actually usable on my tablet, via CI build
- [ ] Auto-update app via google play?

## Usability priority
- Thumbnail generation needs to be async
- I need to do caching myself otherwise I cannot re-use thumbnail images or video thumbnails
- [ ] Add a background job (like what i did for ktor server) that will fetch images and create thumbnails to save
- [ ] Find a way to render a thumbnail from a video in <10s
- [ ] Add back video thumbnail generation