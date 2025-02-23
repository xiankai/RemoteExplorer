## Feature Priority
- [ ] Inline search
- [ ] Display filter (hide metadata files)
- [ ] Video playback needs to work when seeking

## Get in my hands priority
- [ ] Figure out how to get my app actually usable on my tablet

## Usability priority
- Thumbnail generation needs to be async
- I need to do caching myself otherwise I cannot re-use thumbnail images or video thumbnails
- [ ] Add a background job (like what i did for ktor server) that will fetch images and create thumbnails to save
- [ ] Push to the background job whenever the view changes 
- [ ] Find a way to render a thumbnail from a video in <10s
- [ ] Put breadcrumbs, actions bar, search bar, back button on 2 separate lines