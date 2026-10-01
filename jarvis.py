import datetime, os, platform, subprocess, webbrowser
import pyttsx3
import speech_recognition as sr

NAME="JARVIS"

def speak(text):
    print(f"JARVIS: {text}")
    engine=pyttsx3.init()
    engine.setProperty("rate", 190)
    voices=engine.getProperty("voices")
    if voices: engine.setProperty("voice", voices[0].id)
    engine.say(text); engine.runAndWait()

def listen():
    r=sr.Recognizer(); r.energy_threshold=300
    with sr.Microphone() as source:
        print("Listening...")
        r.adjust_for_ambient_noise(source, duration=0.25)
        try:
            audio=r.listen(source, timeout=5, phrase_time_limit=8)
            return r.recognize_google(audio)
        except Exception:
            return ""

def handle(q):
    q=q.lower().strip()
    if not q: return True
    if "time" in q:
        speak(datetime.datetime.now().strftime("It is %I:%M %p"))
    elif "date" in q:
        speak(datetime.datetime.now().strftime("Today is %A, %d %B %Y"))
    elif q.startswith("open "):
        target=q[5:].strip()
        urls={"youtube":"https://youtube.com","google":"https://google.com","github":"https://github.com"}
        webbrowser.open(urls.get(target, "https://www.google.com/search?q="+target.replace(" ","+")))
        speak("Opening "+target)
    elif "system" in q or "computer" in q:
        speak(f"You are running {platform.system()} {platform.release()}.")
    elif "shutdown" in q:
        speak("Shutdown is disabled for safety. Use the operating system controls.")
    elif q in ("exit","quit","goodbye","stop"):
        speak("Goodbye, Sir.")
        return False
    else:
        speak("I heard you. For this starter build, that command is not implemented yet.")
    return True

if __name__=="__main__":
    speak("All systems online, Sir. JARVIS is ready.")
    while True:
        if not handle(listen()): break
