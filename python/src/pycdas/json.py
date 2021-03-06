import json
from utilities import  *

class JSONObject:

    def __init__( self, spec={}, **args ):
        self.items = {}
        self.load_spec( spec )
        self.process_spec( **args )

    def load_spec( self, spec ):
        if isinstance( spec, JSONObject ): spec = dict(spec.spec)
        self.spec = {} if (spec is None) else convert_json_str( spec )
        if not isinstance( self.spec, dict ):
            raise Exception( "Unrecognized JSONObject spec: " + str(spec) )

    def get_spec(self):
        return self.items

    @property
    def id(self):
        return self.items.get( "id", None )

    def equals( self, region ):
        for (key,value) in self.items.iteritems():
            if region.get(key) <> value: return False
        return True

    def process_spec( self, **args ):
        self.items = dict( self.spec )

    def __str__( self ):
        return str(self.items)

    def __len__( self ):
        return len( self.items )

    def get(self, key, default_val=None ):
        return self.items.get( key, default_val )

    def items(self):
        return self.items.items()

    def iteritems(self):
        return self.items.iteritems()

    def __getitem__(self, item):
        return self.items.get( item, None )

    def __setitem__(self, key, value):
        self.items[ key ] = value

    def getItem( self, item_name ):
        return self.items.get( item_name, None )

    def getSpec(self, spec_name ):
        return self.spec.get( spec_name, None )

    def specs(self):
        return self.spec.items()

class JSONObjectContainer:

    def __init__( self, spec=None ):
        if ( isinstance( spec, JSONObjectContainer ) or isinstance( spec, JSONObject ) ): spec = spec.spec
        self._objects = []
        self.process_spec( spec )

    def newObject( self, spec ):
        return JSONObject(spec)

    def process_spec( self, spec ):
        if spec:
            spec = convert_json_str( spec )
            if isinstance( spec, list ):
                for object_spec in spec:
                    self._objects.append( self.newObject( object_spec) )
            elif isinstance( spec, dict ):
                self._objects.append( self.newObject( spec) )
            else:
                raise Exception( "Unrecognized JSONObject spec: " + str(spec) )

    @property
    def value(self):
        return self._objects[0] if len(self._objects) else None

    @property
    def values(self):
        return self._objects

    def getValue( self, id, use_default = False ):
        if id is not None:
            for obj in self._objects:
                if obj.id == id:
                    return obj
        if use_default: return self.value
