/* Prehrávač živého obrazu H.264 (MSE) s návratom na MJPEG – Firesport Cam */
function fsPlay(video,q,onFallback){
 if(!window.MediaSource||!MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01F"')){onFallback();return}
 let played=false,done=false,lastT=-1,stall=0;const ctrl=new AbortController();
 const ms=new MediaSource();video.src=URL.createObjectURL(ms);
 function end(){if(done)return;done=true;clearInterval(wd);try{ctrl.abort()}catch(e){}
  if(played)setTimeout(function(){fsPlay(video,q,onFallback)},800);else onFallback();}
 ms.addEventListener('sourceopen',async function(){
  try{
   const r=await fetch('/live.mp4?'+q+'&t='+Date.now(),{signal:ctrl.signal});
   if(!r.ok){end();return}
   const codec=r.headers.get('X-Codec')||'avc1.42E01F';
   const sb=ms.addSourceBuffer('video/mp4; codecs="'+codec+'"');
   const queue=[];
   function pump(){
    if(sb.updating||!queue.length)return;
    try{if(video.buffered.length&&video.currentTime-video.buffered.start(0)>20){sb.remove(video.buffered.start(0),video.currentTime-5);return}}catch(e){}
    let n=0;for(const c of queue)n+=c.length;const b=new Uint8Array(n);let o=0;
    while(queue.length){const c=queue.shift();b.set(c,o);o+=c.length}
    try{sb.appendBuffer(b)}catch(e){end()}
   }
   sb.addEventListener('updateend',function(){
    if(video.buffered.length){const e=video.buffered.end(video.buffered.length-1);
     if(e-video.currentTime>0.5)video.currentTime=Math.max(e-0.1,0);}
    if(video.paused)video.play().catch(function(){});
    pump();
   });
   const rd=r.body.getReader();
   for(;;){const x=await rd.read();if(x.done)break;queue.push(x.value);pump();}
   end();
  }catch(e){end()}
 });
 const wd=setInterval(function(){
  if(video.currentTime>0.2)played=true;
  if(video.currentTime===lastT){if(++stall>=6)end()}else{stall=0;lastT=video.currentTime}
 },1000);
}
