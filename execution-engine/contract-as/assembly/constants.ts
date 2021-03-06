export const UREF_ADDR_LENGTH = 32;
export const OPTION_TAG_SERIALIZED_LENGTH = 1;
export const ACCESS_RIGHTS_SERIALIZED_LENGTH = 1;
export const UREF_SERIALIZED_LENGTH = UREF_ADDR_LENGTH + OPTION_TAG_SERIALIZED_LENGTH + ACCESS_RIGHTS_SERIALIZED_LENGTH;

export const KEY_ID_SERIALIZED_LENGTH: usize = 1; // u8 used to determine the ID
export const KEY_UREF_SERIALIZED_LENGTH = KEY_ID_SERIALIZED_LENGTH + UREF_SERIALIZED_LENGTH;

export const PURSE_ID_SERIALIZED_LENGTH = UREF_SERIALIZED_LENGTH;
