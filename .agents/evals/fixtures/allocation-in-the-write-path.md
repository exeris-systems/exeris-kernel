Task — the HTTP/2 write path allocates a fresh `byte[]` per DATA frame, and a JFR recording
from the 0.12 stress gate shows 5 GC cycles per second under 30k rps. Make it stop.
