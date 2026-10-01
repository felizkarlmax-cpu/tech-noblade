import datetime, json, os, platform, webbrowser
import pyttsx3
import speech_recognition as sr

MEMORY_FILE = "jarvis_memory.json"

def load_memory():
    if not os.path.exists(MEMORY_FILE):
        return {}
    try:
        with open(MEMORY_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}

def save_memory(memory):
    with open(MEMORY_FILE, "w", encoding="utf-8") as f:
        json.dump(memory, f, indent=2, ensure_ascii=False)

memory = load_memory()

def speak(text):
    print(f"JARVIS: {text}")
    engine = pyttsx3.init()
    engine.setProperty("rate", 190)
    voices = engine.getProperty("voices")
    if voices:
        engine.setProperty("voice", voices[0].id)
    engine.say(text)
    engine.runAndWait()

def listen():
    r = sr.Recognizer()
    r.energy_threshold = 300
    with sr.Microphone() as source:
        print("Listening...")
        r.adjust_for_ambient_noise(source, duration=0.2)
        try:
            audio = r.listen(source, timeout=5, phrase_time_limit=8)
            return r.recognize_google(audio)
        except Exception:
            return ""

def remember(q):
    text = q.strip()
    lower = text.lower()
    for p in ("remember that ", "remember ", "save that ", "save "):
        if lower.startswith(p):
            value = text[len(p):].strip()
            if value:
                memory.setdefault("notes", []).append(value)
                save_memory(memory)
                speak("Saved it, Sir.")
                return True
    return False

def recall(q):
    lower = q.lower()
    if "what do you remember" in lower or "show my memory" in lower:
        notes = memory.get("notes", [])
        speak("My memory is currently empty, Sir." if not notes else "I remember: " + "; ".join(notes[-5:]))
        return True
    if "forget everything" in lower or "clear my memory" in lower:
        memory.clear()
        save_memory(memory)
        speak("Local memory cleared, Sir.")
        return True
    return False

def handle(q):
    q = q.strip()
    lower = q.lower()
    if not q: return True
    if remember(q) or recall(q): return True
    if "time" in lower:
        speak(datetime.datetime.now().strftime("It is %I:%M %p"))
    elif "date" in lower:
        speak(datetime.datetime.now().strftime("Today is %A, %d %B %Y"))
    elif lower.startswith("open "):
        target = lower[5:].strip()
        urls = {"youtube":"https://youtube.com","google":"https://google.com","github":"https://github.com"}
        webbrowser.open(urls.get(target, "https://www.google.com/search?q=" + target.replace(" ","+")))
        speak("Opening " + target)
    elif "system" in lower or "computer" in lower:
        speak(f"You are running {platform.system()} {platform.release()}.")
    elif lower in ("exit","quit","goodbye","stop"):
        speak("Goodbye, Sir.")
        return False
    else:
        speak("I heard you. Try remember, what do you remember, time, date, or open Google.")
    return True

if __name__ == "__main__":
    speak("All systems online, Sir. Memory system ready.")
    while True:
        if not handle(listen()): break
