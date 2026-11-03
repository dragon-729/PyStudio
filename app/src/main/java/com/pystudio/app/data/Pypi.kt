package com.pystudio.app.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal PyPI client. PyPI has disabled its search API, so lookup is by exact name. */
object Pypi {
    data class Info(val name: String, val version: String, val summary: String, val requires: String, val home: String)

    fun lookup(name: String): Info? = try {
        val u = URL("https://pypi.org/pypi/${URLEncoder.encode(name.trim(), "UTF-8")}/json")
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "PyStudio")
        if (c.responseCode != 200) null else {
            val o = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("info")
            fun s(k: String) = o.optString(k).let { if (it == "null") "" else it }
            Info(s("name"), s("version"), s("summary"), s("requires_python"), s("home_page"))
        }
    } catch (e: Exception) {
        null
    }

    val popular = ("numpy pandas requests flask django fastapi scipy matplotlib pillow beautifulsoup4 lxml " +
        "sqlalchemy pytest black flake8 pylint mypy click typer rich colorama tqdm pyyaml toml tomli jinja2 " +
        "markdown pygments httpx aiohttp urllib3 certifi idna charset-normalizer six python-dateutil pytz " +
        "tzdata packaging setuptools wheel attrs pydantic regex cryptography pycryptodome bcrypt pyjwt " +
        "websockets websocket-client paramiko pexpect psutil openpyxl xlsxwriter xlrd reportlab fpdf2 " +
        "qrcode pyqrcode scikit-learn seaborn sympy networkx statsmodels plotly bokeh dash streamlit " +
        "opencv-python-headless nltk spacy gensim joblib numba cython pybind11 pyserial pyperclip " +
        "emoji faker unidecode humanize arrow pendulum schedule apscheduler redis pymongo psycopg2-binary " +
        "mysql-connector-python peewee tinydb pyfiglet termcolor tabulate prettytable texttable docopt " +
        "argparse-addons decorator more-itertools toolz sortedcontainers cachetools tenacity retry " +
        "python-dotenv environs loguru structlog pytest-cov hypothesis tox nox pre-commit autopep8 " +
        "pyflakes pycodestyle jedi parso isort yapf ruff python-telegram-bot discord.py tweepy " +
        "yt-dlp pytube youtube-dl googletrans deep-translator gtts pyttsx3 speechrecognition").split(" ")
}
