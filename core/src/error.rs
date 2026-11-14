use thiserror::Error;

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("Network error: {0}")]
    Http(#[from] reqwest::Error),

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),

    #[error("URL parse error: {0}")]
    Url(#[from] url::ParseError),

    #[error("Cryptography error: {0}")]
    Crypto(String),

    #[error("Authentication required or failed: {0}")]
    Auth(String),

    #[error("Protocol error: {0}")]
    Protocol(String),

    #[error("Invalid input: {0}")]
    InvalidInput(String),

    #[error("Not found: {0}")]
    NotFound(String),

    #[error("Subprocess error: {0}")]
    Subprocess(String),

    #[error("Torrent engine error: {0}")]
    Torrent(String),
}

pub type CoreResult<T> = Result<T, CoreError>;
