//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {U512} from "../../../../contract-as/assembly/bignum";
import {getMainPurse} from "../../../../contract-as/assembly/account";
import {Key} from "../../../../contract-as/assembly/key";
import {PurseId, TransferredTo} from "../../../../contract-as/assembly/purseid";
import {putKey} from "../../../../contract-as/assembly";
import {CLValue} from "../../../../contract-as/assembly/clvalue";


const TRANSFER_RESULT_UREF_NAME = "transfer_result";
const MAIN_PURSE_FINAL_BALANCE_UREF_NAME = "final_balance";

enum Args{
    DestinationAccount = 0,
    Amount = 1,
}

enum CustomError{
    MissingAmountArg = 1,
    InvalidAmountArg = 2,
    MissingDestinationAccountArg = 3,
    UnableToGetMainPurse = 4,
    UnableToGetBalance = 103
}

export function call(): void {
    const maybeMainPurse = getMainPurse();
    if (maybeMainPurse === null) {
        Error.fromUserError(<u16>CustomError.UnableToGetMainPurse).revert();
        return;
    }
    const mainPurse = <PurseId>maybeMainPurse;
    const destinationAccountAddrArg = CL.getArg(Args.DestinationAccount);
    if (destinationAccountAddrArg === null) {
        Error.fromUserError(<u16>CustomError.MissingDestinationAccountArg).revert();
        return;
    }
    const amountArg = CL.getArg(Args.Amount);
    if (amountArg === null) {
        Error.fromUserError(<u16>CustomError.MissingAmountArg).revert();
        return;
    }
    const amountResult = U512.fromBytes(amountArg);
    if (amountResult.hasError()) {
        Error.fromUserError(<u16>CustomError.InvalidAmountArg).revert();
        return;
    }
    let amount = amountResult.value;
    let message = "";
    const result = mainPurse.transferToAccount(<Uint8Array>destinationAccountAddrArg, amount);
    switch (result) {
        case TransferredTo.NewAccount:
            message = "Ok(NewAccount)";
            break;
        case TransferredTo.ExistingAccount:
            message = "Ok(ExistingAccount)";
            break;
        case TransferredTo.TransferError:
            message = "Err(ApiError::Transfer [" + ErrorCode.Transfer.toString() + "])";
            break;
    }
    const transferResultKey = Key.create(CLValue.fromString(message));
    putKey(TRANSFER_RESULT_UREF_NAME, <Key>transferResultKey);
    const maybeBalance  = mainPurse.getBalance();
    if (maybeBalance === null) {
        Error.fromUserError(<u16>CustomError.UnableToGetBalance).revert();
        return;
    }
    const key = Key.create(CLValue.fromU512(<U512>maybeBalance));
    putKey(MAIN_PURSE_FINAL_BALANCE_UREF_NAME, <Key>key);
}
