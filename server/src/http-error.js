import { I18nError } from './i18n.js';

/** Erreur HTTP dont le message (clé de traduction + variables) est traduit dans la langue du client. */
export class HttpError extends I18nError {
  constructor(status, key, vars) {
    super(key, vars);
    this.status = status;
  }
}
