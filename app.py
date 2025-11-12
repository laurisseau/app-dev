from flask import Flask
app = Flask(__name__)

@app.route('/')
def home():
    return 'Hello, World! ci'

if __name__ == '__main__':
    # Listen on all network interfaces (important for Docker/Kubernetes)
    app.run(port=8080, debug=True)
