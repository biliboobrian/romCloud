// Erreur renvoyée à l'interface : { key, vars, message } (clé du dictionnaire de l'interface, ou
// message du serveur), comme l'application Electron.
use serde::Serialize;
use serde_json::{Map, Value};

#[derive(Debug, Clone, Serialize)]
pub struct AppError {
    pub key: Option<String>,
    pub vars: Map<String, Value>,
    pub message: String,
    /// Code HTTP de la réponse (erreurs du serveur), non transmis à l'interface.
    #[serde(skip)]
    pub status: Option<u16>,
}

pub type Result<T> = std::result::Result<T, AppError>;

fn object(vars: Value) -> Map<String, Value> {
    match vars {
        Value::Object(map) => map,
        _ => Map::new(),
    }
}

impl AppError {
    /// Erreur traduite par l'interface : [key] et ses variables ([vars], objet JSON).
    pub fn new(key: &str, vars: Value) -> Self {
        AppError { key: Some(key.to_string()), vars: object(vars), message: key.to_string(), status: None }
    }

    pub fn with_message(key: &str, vars: Value, message: impl Into<String>) -> Self {
        AppError { key: Some(key.to_string()), vars: object(vars), message: message.into(), status: None }
    }

    /// Erreur sans traduction (message affiché tel quel).
    pub fn msg(message: impl Into<String>) -> Self {
        AppError { key: None, vars: Map::new(), message: message.into(), status: None }
    }

    pub fn is(&self, key: &str) -> bool {
        self.key.as_deref() == Some(key)
    }
}

impl std::fmt::Display for AppError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl From<std::io::Error> for AppError {
    fn from(e: std::io::Error) -> Self {
        AppError::msg(e.to_string())
    }
}

impl From<serde_json::Error> for AppError {
    fn from(e: serde_json::Error) -> Self {
        AppError::msg(e.to_string())
    }
}

impl From<reqwest::Error> for AppError {
    fn from(e: reqwest::Error) -> Self {
        AppError::msg(e.to_string())
    }
}

impl From<zip::result::ZipError> for AppError {
    fn from(e: zip::result::ZipError) -> Self {
        AppError::msg(e.to_string())
    }
}
