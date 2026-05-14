/**
 * Short opaque id generator. 8 chars of base36 = 36^8 ≈ 2.8e12 possibilities — more
 * than enough collision headroom for the volume polls (and future artifact types)
 * will see. Uses {@link crypto.getRandomValues}, which is available in the Workers
 * runtime by default.
 */
const ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';

export function nanoid(length = 8): string {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  let id = '';
  for (let i = 0; i < length; i++) {
    id += ALPHABET[bytes[i] % ALPHABET.length];
  }
  return id;
}
