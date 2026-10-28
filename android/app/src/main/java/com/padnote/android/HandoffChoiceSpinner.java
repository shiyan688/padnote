package com.padnote.android;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Spinner;

/** Spinner-shaped field that opens the handoff flow's branded choice card. */
final class HandoffChoiceSpinner extends Spinner {
    private String choiceTitle="";

    HandoffChoiceSpinner(Context context){super(context);ChoiceRows.styleSpinner(this);}
    HandoffChoiceSpinner(Context context,AttributeSet attrs){super(context,attrs);ChoiceRows.styleSpinner(this);}
    void setChoiceTitle(String title){choiceTitle=title==null?"":title;}

    @Override public boolean performClick(){
        if(getAdapter()==null||getAdapter().getCount()==0)return true;
        int selected=getSelectedItemPosition();String[] rows=new String[getAdapter().getCount()];
        for(int i=0;i<rows.length;i++){
            Object item=getAdapter().getItem(i);String label=item==null?"":item.toString();
            rows[i]=(i==selected?"✓  ":"      ")+label;
        }
        ChoiceRows.builder(getContext()).setTitle(choiceTitle)
                .setAdapter(ChoiceRows.adapter(getContext(),rows),(dialog,which)->setSelection(which))
                .setNegativeButton(R.string.flow_cancel,null).show();
        return true;
    }
}
